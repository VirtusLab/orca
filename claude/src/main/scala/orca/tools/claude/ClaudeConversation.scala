package orca.tools.claude

import orca.agents.{AutoApprove, BackendTag, AgentConfig}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ApprovalDecision, ConversationEvent}
import orca.backend.{ForkedConversation, StreamSource}
import orca.subprocess.PipedCliProcess
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
) extends ForkedConversation[BackendTag.ClaudeCode.type](
      source = StreamSource.fromProcess(process),
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  // The reader thread is the sole writer for the fields below, and reads happen
  // on the same thread within `handle(...)` dispatch — no cross-thread
  // visibility concerns, so plain `var`s suffice.

  /** Captured from the `system.init` message so `handleResult` can fall back to
    * it when the `result` message itself doesn't carry the resolved model id.
    * Some Claude CLI versions emit it in one but not both.
    */
  private var initModel: Option[String] = None

  /** Set when a text or thinking delta streams, cleared when the next full-turn
    * `assistant` message lands. Answers "did the model's prose already stream
    * as deltas since the last full-turn boundary?" for two consumers:
    *   - `handleAssistantTurn` gates its fallback that re-emits Text/Thinking
    *     blocks when no partials arrived;
    *   - `handleResultError` shows a short marker instead of repeating an
    *     `is_error` body that already streamed as deltas.
    *
    * Deliberately NOT the base's `turnIsOpen`, which counts a `ToolResult` as
    * turn-opening activity. After `tool_use → tool_result → is_error` with no
    * assistant text, `turnIsOpen` is `true` while this flag is `false`; wiring
    * `handleResultError` to `turnIsOpen` would point "session failed (see
    * message above)" at a tool result instead of the actual error body.
    */
  private var deltasSinceLastFullTurn: Boolean = false

  /** Tool-use ids suppressed in `handleAssistantTurn` — `ask_user` invocations
    * and (in structured mode) the CLI-injected `StructuredOutput` exit call.
    * `handleUserTurn` drops the matching `tool_result` so the suppressed
    * exchange doesn't re-render. See [[orca.backend.AskUserEchoes]].
    */
  private val askUserEchoes = new orca.backend.AskUserEchoes

  // The ask_user bridge drainer and onFinalize close are owned by the base;
  // this subclass just declares `askUser` on the ctor param. Stdin is closed
  // right after the initial prompt, so mid-session input flows through the MCP
  // tool result.

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def terminalMessageNoun: String = "a result message"

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
      // Unknown top-level message types are protocol drift — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      ()

  /** Full assistant turn, arriving after partials have streamed. Single source
    * of truth for tool calls — claude emits the `assistant` message BEFORE the
    * matching `content_block_stop`, so tool-use events can't stream earlier.
    * Text and thinking normally already streamed as deltas; if none preceded
    * this turn we fall back to emitting each block as a single delta.
    */
  private def handleAssistantTurn(content: List[ContentBlock]): Unit =
    val sawDeltasThisTurn = deltasSinceLastFullTurn
    deltasSinceLastFullTurn = false
    content.foreach:
      // Suppress the agent's own `ask_user` ToolCall — the host-side bridge
      // emits a UserQuestion for the same exchange. Remember the id so
      // `handleUserTurn` also drops the matching tool_result (else the typed
      // answer re-renders).
      case ContentBlock.ToolUse(id, name, _)
          if name == ClaudeBackend.AskUserToolName =>
        askUserEchoes.suppress(id)
      // The CLI-injected structured-output "exit" call (`--json-schema`): the
      // payload reaches the caller via the result message, so rendering the
      // tool call would show the same JSON twice. Gated on structured mode so a
      // genuine user tool named `StructuredOutput` is unaffected in plain runs.
      case ContentBlock.ToolUse(id, name, _)
          if outputSchema.isDefined &&
            name == ClaudeBackend.StructuredOutputToolName =>
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
        // Paired with a suppressed `ask_user` ToolUse; the user already saw
        // their typed answer at the prompt, so don't echo it. `consume` removes
        // the id.
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
    settleSuccess(
      wireId = sid,
      output = structured.orElse(output).getOrElse(""),
      usage = usage,
      // Fall back to the model claude announced in system.init when the
      // result message omits it.
      modelId = model.orElse(initModel)
    )

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth) at the CLI boundary rather than inside a turn. Treat these
    * as session-ending rather than feeding the error body into the response
    * parser, which might otherwise accept a `{"type":"error",...}` payload as
    * valid output. `failWith` carries the full message; the in-stream `Error`
    * event is short if the body already streamed as part of a turn.
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
    * if it contributes only to state surfaced elsewhere. Text and thinking
    * deltas pass straight through; tool-use deltas are NOT translated here,
    * since the full-turn message is the single source of truth for tool calls
    * (see [[handleAssistantTurn]]).
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
