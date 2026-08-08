package orca.tools.claude

import orca.agents.{
  AutoApprove,
  BackendTag,
  AgentConfig,
  Model,
  StructuredOutputMode
}
import orca.AgentTurnFailed
import orca.events.TurnDebit
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
  * plus auto-approve policy for tools listed in `config.autoApprove`. The
  * backend writes the opening user turn; the only outbound write here is a
  * tool-approval response, via `writeOutbound`.
  */
private[claude] class ClaudeConversation(
    process: PipedCliProcess,
    config: AgentConfig,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    override val askUser: Option[orca.backend.mcp.AskUserSession] = None,
    /** Per-turn artefacts this conversation owns — the MCP repo-read binding,
      * the MCP config file, the `--append-system-prompt-file` temp file. Closed
      * once the turn finalizes; the same list goes to `SubprocessSpawn.open`,
      * which covers the failure path. See [[onFinalize]].
      */
    resources: List[AutoCloseable] = Nil
) extends ForkedConversation[BackendTag.ClaudeCode.type](
      source = StreamSource.fromProcess(process),
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  override def structuredOutputMode: StructuredOutputMode =
    ClaudeBackend.StructuredOutputDelivery

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

  /** Ids of the model responses seen since the last result message — the turn's
    * API-call count ([[orca.events.Usage.apiCalls]]), which nothing on the wire
    * reports directly. A set, not a counter: the CLI emits one `assistant`
    * message per content group, several sharing one response id.
    *
    * A turn that dispatches a subagent counts the subagent's responses too —
    * the CLI forwards them on this stream, even though it files them under a
    * separate session transcript. Measured on one such turn: 53 responses
    * counted here against 18 in the dispatching session's own transcript. Its
    * token total is the CLI's aggregate for the whole turn and did not equal
    * the sum of either set, so `promptTokens / apiCalls` means little on a turn
    * like that.
    */
  private var responseIdsThisTurn: Set[String] = Set.empty

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

  /** Release the turn's own artefacts (in reverse), then defer to the base.
    * Load-bearing rather than tidy-up: a leaked MCP binding holds its Netty
    * event-loop threads and its port for the life of the JVM, and a flow runs
    * hundreds of turns. Each close is guarded so one failure can't skip the
    * next.
    */
  override protected def onFinalize(): Unit =
    try
      resources.reverseIterator.foreach: r =>
        try r.close()
        catch case scala.util.control.NonFatal(_) => ()
    finally super.onFinalize()

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def terminalMessageNoun: String = "a result message"

  // --- Per-message dispatch ---

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(_, model) =>
      initModel = model
    case InboundMessage.AssistantTurn(content, messageId) =>
      responseIdsThisTurn ++= messageId
      handleAssistantTurn(content)
    case InboundMessage.UserTurn(content) => handleUserTurn(content)
    case result: InboundMessage.Result =>
      if result.isError then handleResultError(result)
      else handleResult(result)
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

  /** Takes the whole [[InboundMessage.Result]] product (not its `output`/
    * `structuredOutput` fields unpacked positionally) — those two are
    * same-typed `Option[String]` siblings, easy to swap by accident at a call
    * site.
    */
  private def handleResult(result: InboundMessage.Result): Unit =
    settleSuccess(
      wireId = result.sessionId,
      output = resultBody(result).getOrElse(""),
      usage = withApiCalls(result.usage.getOrElse(orca.events.Usage.empty)),
      modelId = turnModel(result)
    )

  /** The turn's model: the one the `result` message reports, falling back to
    * what claude announced in `system.init`.
    */
  private def turnModel(result: InboundMessage.Result): Option[String] =
    result.model.orElse(initModel)

  /** Attaches the turn's API-call count to its usage and clears the tally, so
    * the next turn of a multi-turn conversation counts its own responses.
    *
    * Having seen no response id leaves the count absent rather than zero: a
    * turn that reports tokens made requests, so zero would mean "none happened"
    * where the truth is "none were observed".
    */
  private def withApiCalls(usage: orca.events.Usage): orca.events.Usage =
    val counted =
      if responseIdsThisTurn.isEmpty then usage
      else usage.copy(apiCalls = Some(responseIdsThisTurn.size.toLong))
    responseIdsThisTurn = Set.empty
    counted

  /** The result message's payload: the `--json-schema` validated value when the
    * session ran structured, else the free-form reply; `None` when the message
    * carries neither (or only an empty one). Shared by the success and error
    * paths so the two can't drift on which field is the body.
    */
  private def resultBody(result: InboundMessage.Result): Option[String] =
    result.structuredOutput.orElse(result.output).filter(_.nonEmpty)

  /** orca decodes usage only from the `result` message, and a turn that reaches
    * one settles itself (with an `Observed` debit) rather than reaching the
    * base's generic wrap.
    */
  override protected def failedTurnDebit: TurnDebit = TurnDebit.Unobserved

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth) at the CLI boundary rather than inside a turn. Treat these
    * as session-ending rather than feeding the error body into the response
    * parser, which might otherwise accept a `{"type":"error",...}` payload as
    * valid output. `failWith` carries the full message; the in-stream `Error`
    * event is short if the body already streamed as part of a turn.
    *
    * An empty body is the case that most needs diagnosing — a resume that
    * replays a queued pseudo-turn, an exhausted turn budget — and there the
    * reason lives only in `subtype`, so it stands in for the message rather
    * than leaving a bare "claude reported is_error". `AgentTurnFailed` (not a
    * plain `OrcaFlowException`) because the turn ran and the wire session is
    * already locked: `awaitResult` would classify it as such anyway, and
    * settling it here is what lets the failed turn's `usage` reach the cost
    * summary.
    */
  private def handleResultError(result: InboundMessage.Result): Unit =
    val message = resultBody(result).getOrElse(
      s"claude reported is_error (subtype ${result.subtype})"
    )
    val displayed =
      if deltasSinceLastFullTurn then "session failed (see message above)"
      else message
    eventQueue.enqueue(ConversationEvent.Error(displayed))
    // A frame with no `usage` object saw no tokens — `Observed(Usage.empty)`
    // would reach the cost summary as a measured zero. The wire's sibling
    // `total_cost_usd` is folded into `Usage.cost`, so it is dropped with it.
    val debit = result.usage match
      case Some(u) =>
        TurnDebit.Observed(withApiCalls(u), turnModel(result).map(Model.apply))
      case None => TurnDebit.Unobserved
    failWith(
      new AgentTurnFailed(
        s"claude session failed (subtype ${result.subtype}, " +
          s"session ${result.sessionId}): $message",
        debit
      )
    )

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

  /** Answer a control request, or report that the answer can't be delivered.
    * `ClaudeBackend.openConversation` closes stdin right after the opening
    * turn, so the write always hits a closed pipe. The failure is reported
    * rather than thrown: from the reader thread an `IOException` would look
    * like a parse failure, and from the interactive `ApproveTool` closure it
    * would fail the whole turn with a bare `Stream Closed` that names nothing.
    *
    * Nothing reaches this today: claude 2.1.220 sends no `can_use_tool` over
    * stdio. Delivering a decision would mean keeping stdin open for the turn.
    */
  private def respond(requestId: String, decision: ApprovalDecision): Unit =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    try
      writeOutbound(OutboundMessage.ControlResponse(requestId, controlDecision))
    catch
      case e: java.io.IOException =>
        eventQueue.enqueue(
          ConversationEvent.Error(
            s"could not deliver the tool-approval decision for request " +
              s"$requestId — claude's stdin is closed: ${e.getMessage}"
          )
        )

  private def writeOutbound(msg: OutboundMessage): Unit =
    process.writeLine(OutboundMessage.toJson(msg))
