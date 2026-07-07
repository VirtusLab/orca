package orca.tools.claude

import orca.agents.{AutoApprove, BackendTag, AgentConfig, Model, WireSessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ApprovalDecision, ConversationEvent, AgentResult}
import orca.backend.{ForkedConversation, StreamSource}
import orca.subprocess.PipedCliProcess
import ox.Ox
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}

/** Drives a stream-json conversation with claude to completion.
  *
  * Boilerplate (reader fork, event queue, outcome lifecycle, stderr drain)
  * lives in [[ForkedConversation]]; this class supplies the claude-specific
  * protocol translation: NDJSON → [[InboundMessage]] → `ConversationEvent`s,
  * plus auto-approve policy for tools listed in `config.autoApprove`. Outbound
  * writes (user turns, tool-approval responses) happen on the channel's thread
  * via `writeOutbound`.
  */
private[claude] class ClaudeConversation(
    process: PipedCliProcess,
    config: AgentConfig,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[orca.backend.mcp.AskUserSession] = None
)(using Ox)
    extends ForkedConversation[BackendTag.ClaudeCode.type](
      source = StreamSource.fromProcess(process),
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  // The reader thread is the sole writer for all four fields below.
  // No cross-thread visibility concerns: reads happen on the same thread
  // immediately after writes, within `handle(...)` dispatch. Plain `var`s
  // suffice — atomics would be theatre.

  /** Captured from the `system.init` message so `handleResult` can fall back to
    * it when the `result` message itself doesn't carry the resolved model id.
    * Some Claude CLI versions emit it in one but not both.
    */
  private var initModel: Option[String] = None

  /** Set when a text or thinking delta streams, cleared when the next full-turn
    * `assistant` message lands (reset at the top of `handleAssistantTurn`).
    * Both consumers rely on claude's wire ordering — the full `assistant`
    * message arrives AFTER any partials for the same turn — and ask the same
    * question: "did the model's prose already stream as deltas since the last
    * full-turn boundary?"
    *   - `handleAssistantTurn` gates its fallback that re-emits Text/Thinking
    *     blocks when no partials arrived (older claude builds, partials
    *     disabled);
    *   - `handleResultError` shows a short marker instead of repeating the full
    *     body when an `is_error`'s body already streamed as deltas this turn.
    *
    * NOTE: this is deliberately NOT the base's `turnIsOpen`. `turnIsOpen`
    * counts a `ToolResult` as turn-opening activity (correct for the grammar),
    * but a tool result is not streamed prose. After `tool_use → tool_result →
    * is_error` with no assistant text, `turnIsOpen` is `true` while this flag
    * is `false`; wiring `handleResultError` to `turnIsOpen` would then show
    * "session failed (see message above)" pointing at a tool result instead of
    * surfacing the actual error body. So the flag stays delta-specific.
    */
  private var deltasSinceLastFullTurn: Boolean = false

  /** Tool-use ids of `ask_user` calls suppressed in `handleAssistantTurn`.
    * `handleUserTurn` drops the matching `tool_result` so the user's typed
    * answer doesn't re-render as `⎿ <answer>` right after the UserQuestion
    * prompt already surfaced it. See [[orca.backend.AskUserEchoes]].
    */
  private val askUserEchoes = new orca.backend.AskUserEchoes

  // `canAskUser` + ask_user bridge drainer + onFinalize close are owned by
  // the base; this subclass just declares `askUser` on the ctor param
  // above. Stdin-as-user-channel is closed right after the initial prompt,
  // so mid-session input flows through the MCP tool result either way.

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      "claude exited cleanly but never sent a result message"
    )

  // --- Per-message dispatch ---

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(_, model) =>
      initModel = model
    case InboundMessage.AssistantTurn(content) => handleAssistantTurn(content)
    case InboundMessage.UserTurn(content)      => handleUserTurn(content)
    case InboundMessage.Result(
          _,
          sid,
          output,
          structured,
          usage,
          isError,
          model
        ) =>
      if isError then handleResultError(output)
      else handleResult(sid, output, structured, usage, model)
    case InboundMessage.ControlRequest(reqId, body) =>
      handleControlRequest(reqId, body)
    case InboundMessage.StreamEvent(payload) =>
      translateStreamEvent(payload).foreach { evt =>
        evt match
          case _: ConversationEvent.AssistantTextDelta |
              _: ConversationEvent.AssistantThinkingDelta =>
            deltasSinceLastFullTurn = true
          case _ => ()
        eventQueue.enqueue(evt)
      }
    case InboundMessage.Unknown(_) =>
      // Unknown top-level message types are protocol drift (new
      // message kinds in newer CLI versions, etc.) — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      ()

  /** Full assistant turn arrives after partials have streamed. This is the
    * single source of truth for tool calls — claude's protocol emits the
    * `assistant` message BEFORE the matching `content_block_stop`, so we can't
    * usefully stream tool-use events earlier. Text and thinking are normally
    * already streamed as deltas; if no deltas preceded this turn (older claude
    * builds, partials disabled) we fall back to emitting each block as a single
    * delta.
    */
  private def handleAssistantTurn(content: List[ContentBlock]): Unit =
    val sawDeltasThisTurn = deltasSinceLastFullTurn
    deltasSinceLastFullTurn = false
    content.foreach:
      // Suppress the agent's own ToolCall block for `ask_user` — the
      // host-side bridge emits a UserQuestion event for the same exchange
      // and rendering the tool-call line on top of it is just noise.
      // Remember the id so `handleUserTurn` can also drop the matching
      // tool_result (otherwise the user's typed answer re-renders as
      // `⎿ <answer>` after the prompt already surfaced it).
      case ContentBlock.ToolUse(id, name, _)
          if name == ClaudeBackend.AskUserToolName =>
        askUserEchoes.suppress(id)
      case ContentBlock.ToolUse(_, name, rawInput) =>
        eventQueue.enqueue(ConversationEvent.AssistantToolCall(name, rawInput))
      case ContentBlock.Text(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      case ContentBlock.Thinking(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
      case _ => ()
    eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)

  /** User turns arriving from the subprocess echo our own input, except they
    * also carry `tool_result` blocks the SDK injected after running a tool —
    * surface those so the channel can render the outcome.
    */
  private def handleUserTurn(content: List[ContentBlock]): Unit =
    content.foreach:
      case ContentBlock.ToolResult(toolUseId, _, _)
          if askUserEchoes.consume(toolUseId) =>
        // Paired with a suppressed `ask_user` ToolUse; the user has already
        // seen their own typed answer at the prompt, so don't echo it back.
        // `consume` removes the id as it matches.
        ()
      case ContentBlock.ToolResult(_, body, isError) =>
        eventQueue.enqueue(
          ConversationEvent.ToolResult(
            // claude's tool_result block carries only a tool_use_id, not the
            // name — the grammar legalizes None here (see ConversationEvent).
            toolName = None,
            ok = !isError,
            content = body
          )
        )
      case _ => ()

  private def handleResult(
      sid: String,
      output: Option[String],
      structured: Option[String],
      usage: Usage,
      model: Option[String]
  ): Unit =
    val result = AgentResult(
      wireId = WireSessionId[BackendTag.ClaudeCode.type](sid),
      output = structured.orElse(output).getOrElse(""),
      usage = usage,
      // Fall back to the model claude announced in system.init when the
      // result message omits it.
      model = model.orElse(initModel).map(Model.apply)
    )
    succeedWith(result)

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth problems) that happen at the CLI boundary rather than inside
    * a turn. Treat these as session-ending failures rather than feeding the
    * error body into the downstream response parser, which might otherwise
    * accept a `{"type":"error",...}` payload as a structurally valid agent
    * output. `failWith` carries the full message for `awaitResult` to surface;
    * the in-stream `Error` event is short if the user already saw the body as
    * part of a streamed turn.
    */
  private def handleResultError(output: Option[String]): Unit =
    val message =
      output.filter(_.nonEmpty).getOrElse("claude reported is_error")
    val displayed =
      if deltasSinceLastFullTurn then "session failed (see message above)"
      else message
    eventQueue.enqueue(ConversationEvent.Error(displayed))
    failWith(new OrcaFlowException(s"claude session failed: $message"))

  private def handleControlRequest(
      requestId: String,
      body: ControlRequestBody
  ): Unit = body match
    case ControlRequestBody.CanUseTool(name, rawInput) =>
      if autoApproves(name) then respond(requestId, ApprovalDecision.Allow())
      else
        eventQueue.enqueue(
          ConversationEvent.ApproveTool(
            toolName = name,
            rawInput = rawInput,
            respond = decision => respond(requestId, decision)
          )
        )
    case ControlRequestBody.Unknown(subtype) =>
      eventQueue.enqueue(
        ConversationEvent.Error(s"Unknown control_request subtype: $subtype")
      )

  private def autoApproves(toolName: String): Boolean = config.autoApprove match
    case AutoApprove.All         => true
    case AutoApprove.Only(tools) => tools.contains(toolName)

  /** Translate one stream-event payload into a `ConversationEvent`, or `None`
    * if the payload contributes only to state we'll surface elsewhere.
    *
    * Text and thinking deltas pass straight through — claude chunks them and
    * the renderer handles re-assembly. Tool-use deltas (start / json / stop)
    * are NOT translated here: in the live protocol the full `assistant` message
    * arrives BEFORE the matching `content_block_stop`, so emitting from a stop
    * event would always be a duplicate of what `handleAssistantTurn` already
    * emitted. The full-turn message is the single source of truth for tool
    * calls.
    */
  private def translateStreamEvent(
      payload: StreamEventPayload
  ): Option[ConversationEvent] = payload match
    case StreamEventPayload.TextDelta(_, text) =>
      Some(ConversationEvent.AssistantTextDelta(text))
    case StreamEventPayload.ThinkingDelta(_, text) =>
      Some(ConversationEvent.AssistantThinkingDelta(text))
    case _ =>
      None // tool-use blocks, block start/stop, unhandled — driver ignores

  private def respond(requestId: String, decision: ApprovalDecision): Unit =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    writeOutbound(OutboundMessage.ControlResponse(requestId, controlDecision))

  private def writeOutbound(msg: OutboundMessage): Unit =
    process.writeLine(OutboundMessage.toJson(msg))
