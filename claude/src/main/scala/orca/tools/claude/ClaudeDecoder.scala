package orca.tools.claude

import orca.agents.{BackendTag, Model, WireSessionId}
import orca.events.{TurnDebit, Usage}
import orca.backend.{
  AgentResult,
  AskUserChannel,
  AskUserEchoes,
  LiveTurn,
  TurnEvent,
  TurnSpec,
  LineDecoder,
  Settled,
  Step,
  DecodedTurn,
  StreamSource
}
import orca.subprocess.PipedCliProcess
import orca.tools.claude.streamjson.{
  ContentBlock,
  InboundMessage,
  StreamEventPayload
}

import ox.Ox

/** Decodes a stream-json conversation with claude: NDJSON → [[InboundMessage]]
  * → `TurnEvent`s.
  */
private[claude] final class ClaudeDecoder(outputSchema: Option[String])
    extends LineDecoder[BackendTag.ClaudeCode.type, ClaudeDecoder.State]:

  import ClaudeDecoder.State

  private type Out = Step[BackendTag.ClaudeCode.type, State]

  def backendName: String = "claude"

  def terminalMessageNoun: String = "a result message"

  def init: State = State(
    initModel = None,
    deltasSinceLastFullMessage = false,
    responseIds = Set.empty,
    echoes = AskUserEchoes.empty
  )

  def line(state: State, line: String): Out =
    InboundMessage.parse(line) match
      case InboundMessage.SystemInit(_, model) =>
        Step.continue(state.copy(initModel = model))
      case InboundMessage.Assistant(content, messageId) =>
        assistantMessage(
          state.copy(responseIds = state.responseIds ++ messageId),
          content
        )
      case InboundMessage.User(content) => userMessage(state, content)
      case result: InboundMessage.Result =>
        if result.isError then resultError(state, result)
        else resultSuccess(state, result)
      case InboundMessage.ControlRequest(subtype) =>
        Step.continue(state, unexpectedControlRequest(subtype))
      case InboundMessage.StreamEvent(payload) =>
        translateStreamEvent(payload) match
          case Some(delta) =>
            Step.continue(state.copy(deltasSinceLastFullMessage = true), delta)
          case None => Step.continue(state)
      // Unknown top-level message types are protocol drift — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      case InboundMessage.Unknown(_) => Step.continue(state)

  /** orca decodes usage only from the `result` message, and a turn that reaches
    * one settles itself with an `Observed` debit.
    */
  def failedTurnDebit(state: State): TurnDebit = TurnDebit.Unobserved

  /** Full assistant message, arriving after partials have streamed. Single
    * source of truth for tool calls — claude emits the `assistant` message
    * BEFORE the matching `content_block_stop`, so tool-use events can't stream
    * earlier. Text and thinking normally already streamed as deltas; if none
    * preceded this message we fall back to emitting each block as a single
    * delta.
    */
  private def assistantMessage(state: State, content: List[ContentBlock]): Out =
    val sawDeltas = state.deltasSinceLastFullMessage
    val (echoes, events) =
      content.foldLeft((state.echoes, Vector.empty[TurnEvent])):
        case ((echoes, events), block) =>
          block match
            // Suppress the agent's own `ask_user` ToolCall — the host-side
            // bridge emits a UserQuestion for the same exchange. Remember the
            // id so `userMessage` also drops the matching tool_result (else the
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
                events :+ TurnEvent.AssistantToolCall(name, rawInput)
              )
            case ContentBlock.Text(text) if !sawDeltas =>
              (echoes, events :+ TurnEvent.AssistantTextDelta(text))
            case ContentBlock.Thinking(text) if !sawDeltas =>
              (echoes, events :+ TurnEvent.AssistantThinkingDelta(text))
            case _ => (echoes, events)
    Step.Continue(
      state.copy(deltasSinceLastFullMessage = false, echoes = echoes),
      (events :+ TurnEvent.AssistantMessageEnd).toList
    )

  /** User messages arriving from the subprocess echo our own input, except they
    * also carry `tool_result` blocks the SDK injected after running a tool —
    * surface those so the channel can render the outcome.
    */
  private def userMessage(state: State, content: List[ContentBlock]): Out =
    val (echoes, events) =
      content.foldLeft((state.echoes, Vector.empty[TurnEvent])):
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
  ): TurnEvent =
    PermissionRefusal.toolName(body) match
      case Some(tool) if isError => TurnEvent.ToolDenied(tool)
      case _ =>
        TurnEvent.ToolResult(
          // claude's tool_result block carries only a tool_use_id, not the
          // name — the grammar legalizes None here (see TurnEvent).
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
    * event is short if the body already streamed as part of a message.
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
      if state.deltasSinceLastFullMessage then "turn failed (see message above)"
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
      List(TurnEvent.Error(displayed)),
      Settled.Failed(
        s"claude turn failed (subtype ${result.subtype}, " +
          s"session ${result.sessionId}): $message",
        debit
      )
    )

  /** claude only sends a control request when asked to prompt over stdio, which
    * orca never does, and stdin is closed so no answer could reach it.
    */
  private def unexpectedControlRequest(subtype: String): TurnEvent =
    TurnEvent.Error(
      s"claude sent an unexpected control_request ($subtype) that orca cannot " +
        "answer: its stdin is closed. A claude CLI change or a flag enabling " +
        "stdio permission prompts may have caused it."
    )

  /** Translate one stream-event payload into a `TurnEvent`, or `None` if it
    * contributes only to state surfaced elsewhere. Text and thinking deltas
    * pass straight through; tool-use deltas are NOT translated here, since the
    * full `assistant` message is the single source of truth for tool calls (see
    * [[assistantMessage]]).
    */
  private def translateStreamEvent(
      payload: StreamEventPayload
  ): Option[TurnEvent] = payload match
    case StreamEventPayload.TextDelta(_, text) =>
      Some(TurnEvent.AssistantTextDelta(text))
    case StreamEventPayload.ThinkingDelta(_, text) =>
      Some(TurnEvent.AssistantThinkingDelta(text))
    case _ =>
      None // tool-use blocks, block start/stop, unhandled — decoder ignores

private[claude] object ClaudeDecoder:

  /** @param initModel
    *   the model `system.init` announced, for a `result` message that doesn't
    *   carry the resolved model id — some Claude CLI versions emit it in one
    *   but not both
    * @param deltasSinceLastFullMessage
    *   whether text or thinking streamed as deltas since the last full
    *   `assistant` message: `assistantMessage` re-emits Text/Thinking blocks
    *   only when none did, and `resultError` shows a short marker instead of
    *   repeating an `is_error` body that already streamed. Not the same as an
    *   open message, which a `ToolResult` also opens: after `tool_use →
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
    *   tool-use ids suppressed in `assistantMessage` — `ask_user` invocations
    *   and (in structured mode) the CLI-injected `StructuredOutput` exit call —
    *   whose `tool_result` `userMessage` drops
    */
  final case class State(
      initModel: Option[String],
      deltasSinceLastFullMessage: Boolean,
      responseIds: Set[String],
      echoes: AskUserEchoes
  )

private[claude] object ClaudeTurn:

  /** Starts decoding `process` into the caller's turn scope. */
  def apply(
      process: PipedCliProcess,
      openingPrompt: Option[String] = None,
      outputSchema: Option[String] = None,
      askUser: AskUserChannel = AskUserChannel.Unavailable
  )(using Ox): LiveTurn[BackendTag.ClaudeCode.type] =
    DecodedTurn.start(
      StreamSource.fromProcess(process),
      TurnSpec(
        openingPrompt = openingPrompt,
        outputSchema = outputSchema,
        structuredOutputMode = ClaudeBackend.StructuredOutputDelivery,
        askUser = askUser
      ),
      ClaudeDecoder(outputSchema)
    )
