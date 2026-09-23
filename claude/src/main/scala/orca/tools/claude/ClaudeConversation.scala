package orca.tools.claude

import orca.agents.{AutoApprove, AgentConfig, BackendTag, Model, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.backend.{
  AgentResult,
  ApprovalDecision,
  AskUserChannel,
  AskUserEchoes,
  Conversation,
  ConversationEvent,
  ConversationSpec,
  LineDecoder,
  NeutralEvent,
  Settled,
  Step,
  StreamConversation,
  StreamSource
}
import orca.backend.mcp.AskUserSession
import orca.subprocess.PipedCliProcess
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}

import ox.Ox

/** Decodes a stream-json conversation with claude: NDJSON → [[InboundMessage]]
  * → `ConversationEvent`s, plus the auto-approve policy for tools listed in
  * `config.autoApprove`. The backend writes the opening user turn; the only
  * write here is a tool-approval response.
  *
  * @param neutral
  *   reports a tool-approval answer the channel gave that can't be delivered
  */
private[claude] final class ClaudeDecoder(
    process: PipedCliProcess,
    config: AgentConfig,
    outputSchema: Option[String],
    neutral: NeutralEvent => Unit
) extends LineDecoder[BackendTag.ClaudeCode.type, ClaudeDecoder.State]:

  import ClaudeDecoder.State

  private type Out = Step[BackendTag.ClaudeCode.type, State]

  def backendName: String = "claude"

  def terminalMessageNoun: String = "a result message"

  def init: State =
    State(None, deltasSinceLastFullTurn = false, Set.empty, AskUserEchoes.empty)

  def line(state: State, line: String): Out =
    InboundMessage.parse(line) match
      case InboundMessage.SystemInit(_, model) =>
        Step.continue(state.copy(initModel = model))
      case InboundMessage.AssistantTurn(content, messageId) =>
        assistantTurn(
          state.copy(responseIds = state.responseIds ++ messageId),
          content
        )
      case InboundMessage.UserTurn(content) => userTurn(state, content)
      case result: InboundMessage.Result =>
        if result.isError then resultError(state, result)
        else resultSuccess(state, result)
      case InboundMessage.ControlRequest(reqId, body) =>
        Step.Continue(state, controlRequest(reqId, body).toList)
      case InboundMessage.StreamEvent(payload) =>
        translateStreamEvent(payload) match
          case Some(delta) =>
            Step.continue(state.copy(deltasSinceLastFullTurn = true), delta)
          case None => Step.continue(state)
      // Unknown top-level message types are protocol drift — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      case InboundMessage.Unknown(_) => Step.continue(state)

  /** orca decodes usage only from the `result` message, and a turn that reaches
    * one settles itself with an `Observed` debit.
    */
  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  /** Full assistant turn, arriving after partials have streamed. Single source
    * of truth for tool calls — claude emits the `assistant` message BEFORE the
    * matching `content_block_stop`, so tool-use events can't stream earlier.
    * Text and thinking normally already streamed as deltas; if none preceded
    * this turn we fall back to emitting each block as a single delta.
    */
  private def assistantTurn(state: State, content: List[ContentBlock]): Out =
    val sawDeltas = state.deltasSinceLastFullTurn
    val (echoes, events) =
      content.foldLeft((state.echoes, Vector.empty[ConversationEvent])):
        case ((echoes, events), block) =>
          block match
            // Suppress the agent's own `ask_user` ToolCall — the host-side
            // bridge emits a UserQuestion for the same exchange. Remember the
            // id so `userTurn` also drops the matching tool_result (else the
            // typed answer re-renders).
            case ContentBlock.ToolUse(id, name, _)
                if name == ClaudeBackend.AskUserToolName =>
              (echoes.suppress(id), events)
            // The CLI-injected structured-output "exit" call (`--json-schema`):
            // the payload reaches the caller via the result message, so
            // rendering the tool call would show the same JSON twice. Gated on
            // structured mode so a genuine user tool named `StructuredOutput`
            // is unaffected in plain runs.
            case ContentBlock.ToolUse(id, name, _)
                if outputSchema.isDefined &&
                  name == ClaudeBackend.StructuredOutputToolName =>
              (echoes.suppress(id), events)
            case ContentBlock.ToolUse(_, name, rawInput) =>
              (
                echoes,
                events :+ ConversationEvent.AssistantToolCall(name, rawInput)
              )
            case ContentBlock.Text(text) if !sawDeltas =>
              (echoes, events :+ ConversationEvent.AssistantTextDelta(text))
            case ContentBlock.Thinking(text) if !sawDeltas =>
              (echoes, events :+ ConversationEvent.AssistantThinkingDelta(text))
            case _ => (echoes, events)
    Step.Continue(
      state.copy(deltasSinceLastFullTurn = false, echoes = echoes),
      (events :+ ConversationEvent.AssistantTurnEnd).toList
    )

  /** User turns arriving from the subprocess echo our own input, except they
    * also carry `tool_result` blocks the SDK injected after running a tool —
    * surface those so the channel can render the outcome.
    */
  private def userTurn(state: State, content: List[ContentBlock]): Out =
    val (echoes, events) =
      content.foldLeft((state.echoes, Vector.empty[ConversationEvent])):
        case ((echoes, events), ContentBlock.ToolResult(id, body, isError)) =>
          echoes.consume(id) match
            // Paired with a suppressed `ask_user` ToolUse; the user already saw
            // their typed answer at the prompt, so don't echo it.
            case Some(rest) => (rest, events)
            case None => (echoes, events :+ toolResultEvent(body, isError))
        case (acc, _) => acc
    Step.Continue(state.copy(echoes = echoes), events.toList)

  /** A refusal arrives as a failed `tool_result`; any other result is passed on
    * as is.
    */
  private def toolResultEvent(
      body: String,
      isError: Boolean
  ): ConversationEvent =
    PermissionRefusal.toolName(body) match
      case Some(tool) if isError => ConversationEvent.ToolDenied(tool)
      case _ =>
        ConversationEvent.ToolResult(
          // claude's tool_result block carries only a tool_use_id, not the
          // name — the grammar legalizes None here (see ConversationEvent).
          toolName = None,
          ok = !isError,
          content = body
        )

  /** Takes the whole [[InboundMessage.Result]] product (not its `output`/
    * `structuredOutput` fields unpacked positionally) — those two are
    * same-typed `Option[String]` siblings, easy to swap by accident at a call
    * site.
    */
  private def resultSuccess(state: State, result: InboundMessage.Result): Out =
    Step.Settle(
      state,
      Nil,
      Settled.Succeeded(
        AgentResult[BackendTag.ClaudeCode.type](
          WireSessionId(result.sessionId),
          resultBody(result).getOrElse(""),
          withApiCalls(state, result.usage.getOrElse(Usage.empty)),
          turnModel(state, result).map(Model.apply)
        )
      )
    )

  /** The turn's model: the one the `result` message reports, falling back to
    * what claude announced in `system.init`.
    */
  private def turnModel(
      state: State,
      result: InboundMessage.Result
  ): Option[String] =
    result.model.orElse(state.initModel)

  /** Attaches the turn's API-call count to its usage.
    *
    * Having seen no response id leaves the count absent rather than zero: a
    * turn that reports tokens made requests, so zero would mean "none happened"
    * where the truth is "none were observed".
    */
  private def withApiCalls(state: State, usage: Usage): Usage =
    if state.responseIds.isEmpty then usage
    else usage.copy(apiCalls = Some(state.responseIds.size.toLong))

  /** The result message's payload: the `--json-schema` validated value when the
    * session ran structured, else the free-form reply; `None` when the message
    * carries neither (or only an empty one). Shared by the success and error
    * paths so the two can't drift on which field is the body.
    */
  private def resultBody(result: InboundMessage.Result): Option[String] =
    result.structuredOutput.orElse(result.output).filter(_.nonEmpty)

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth) at the CLI boundary rather than inside a turn. Treat these
    * as session-ending rather than feeding the error body into the response
    * parser, which might otherwise accept a `{"type":"error",...}` payload as
    * valid output. The settle carries the full message; the in-stream `Error`
    * event is short if the body already streamed as part of a turn.
    *
    * An empty body is the case that most needs diagnosing — a resume that
    * replays a queued pseudo-turn, an exhausted turn budget — and there the
    * reason lives only in `subtype`, so it stands in for the message rather
    * than leaving a bare "claude reported is_error". Settling it here is what
    * lets the failed turn's `usage` reach the cost summary.
    */
  private def resultError(state: State, result: InboundMessage.Result): Out =
    val message = resultBody(result).getOrElse(
      s"claude reported is_error (subtype ${result.subtype})"
    )
    val displayed =
      if state.deltasSinceLastFullTurn then "session failed (see message above)"
      else message
    // A frame with no `usage` object saw no tokens — `Observed(Usage.empty)`
    // would reach the cost summary as a measured zero. The wire's sibling
    // `total_cost_usd` is folded into `Usage.cost`, so it is dropped with it.
    val debit = result.usage match
      case Some(u) =>
        TurnDebit.Observed(
          withApiCalls(state, u),
          turnModel(state, result).map(Model.apply)
        )
      case None => TurnDebit.Unobserved
    Step.Settle(
      state,
      List(ConversationEvent.Error(displayed)),
      Settled.Failed(
        s"claude session failed (subtype ${result.subtype}, " +
          s"session ${result.sessionId}): $message",
        debit
      )
    )

  private def controlRequest(
      requestId: String,
      body: ControlRequestBody
  ): Option[ConversationEvent] = body match
    case ControlRequestBody.CanUseTool(name, _) if autoApproves(name) =>
      respond(requestId, ApprovalDecision.Allow()).left.toOption
    case ControlRequestBody.CanUseTool(name, rawInput) =>
      Some(
        ConversationEvent.ApproveTool(
          toolName = name,
          rawInput = rawInput,
          respond =
            decision => respond(requestId, decision).left.foreach(neutral)
        )
      )
    case ControlRequestBody.Unknown(subtype) =>
      Some(
        ConversationEvent.Error(s"Unknown control_request subtype: $subtype")
      )

  private def autoApproves(toolName: String): Boolean = config.autoApprove match
    case AutoApprove.All         => true
    case AutoApprove.Only(tools) => tools.contains(toolName)

  /** Translate one stream-event payload into a `ConversationEvent`, or `None`
    * if it contributes only to state surfaced elsewhere. Text and thinking
    * deltas pass straight through; tool-use deltas are NOT translated here,
    * since the full-turn message is the single source of truth for tool calls
    * (see [[assistantTurn]]).
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
    * `ClaudeBackend.open` closes stdin right after the opening turn, so the
    * write always hits a closed pipe. The failure is reported rather than
    * thrown: from the reader thread an `IOException` would look like a parse
    * failure, and from the interactive `ApproveTool` closure it would fail the
    * whole turn with a bare `Stream Closed` that names nothing. The reader
    * surfaces it as an `Error`; the closure, running on the consumer, can only
    * trace it.
    *
    * Nothing reaches this today: claude 2.1.220 sends no `can_use_tool` over
    * stdio. Delivering a decision would mean keeping stdin open for the turn.
    */
  private def respond(
      requestId: String,
      decision: ApprovalDecision
  ): Either[ConversationEvent.Error, Unit] =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    try
      Right(
        process.writeLine(
          OutboundMessage.toJson(
            OutboundMessage.ControlResponse(requestId, controlDecision)
          )
        )
      )
    catch
      case e: java.io.IOException =>
        Left(
          ConversationEvent.Error(
            s"could not deliver the tool-approval decision for request " +
              s"$requestId — claude's stdin is closed: ${e.getMessage}"
          )
        )

private[claude] object ClaudeDecoder:

  /** @param initModel
    *   the model `system.init` announced, for a `result` message that doesn't
    *   carry the resolved model id — some Claude CLI versions emit it in one
    *   but not both
    * @param deltasSinceLastFullTurn
    *   whether text or thinking streamed as deltas since the last full-turn
    *   `assistant` message: `assistantTurn` re-emits Text/Thinking blocks only
    *   when none did, and `resultError` shows a short marker instead of
    *   repeating an `is_error` body that already streamed. Not the same as an
    *   open turn, which a `ToolResult` also opens: after `tool_use →
    *   tool_result → is_error` with no assistant text, the marker would point
    *   at a tool result instead of the actual error body.
    * @param responseIds
    *   ids of the model responses seen this turn — its API-call count
    *   ([[orca.events.Usage.apiCalls]]), which nothing on the wire reports
    *   directly. A set, not a counter: the CLI emits one `assistant` message
    *   per content group, several sharing one response id. A turn that
    *   dispatches a subagent counts the subagent's responses too — the CLI
    *   forwards them on this stream, even though it files them under a separate
    *   session transcript. Measured on one such turn: 53 responses counted here
    *   against 18 in the dispatching session's own transcript. Its token total
    *   is the CLI's aggregate for the whole turn and did not equal the sum of
    *   either set, so `promptTokens / apiCalls` means little on a turn like
    *   that.
    * @param echoes
    *   tool-use ids suppressed in `assistantTurn` — `ask_user` invocations and
    *   (in structured mode) the CLI-injected `StructuredOutput` exit call —
    *   whose `tool_result` `userTurn` drops
    */
  final case class State(
      initModel: Option[String],
      deltasSinceLastFullTurn: Boolean,
      responseIds: Set[String],
      echoes: AskUserEchoes
  )

private[claude] object ClaudeConversation:

  /** Starts decoding `process` into the caller's turn scope. */
  def apply(
      process: PipedCliProcess,
      config: AgentConfig,
      initialPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: Option[AskUserSession] = None
  )(using Ox): Conversation[BackendTag.ClaudeCode.type] =
    StreamConversation.start(
      StreamSource.fromProcess(process),
      ConversationSpec(
        openingPrompt = initialPrompt,
        outputSchema = outputSchema,
        structuredOutputMode = ClaudeBackend.StructuredOutputDelivery,
        askUser =
          askUser.fold(AskUserChannel.Unavailable)(AskUserChannel.Mcp(_)),
        onUnsettledEnd = () => ()
      )
    )(neutral => ClaudeDecoder(process, config, outputSchema, neutral))
