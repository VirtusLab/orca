package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import orca.backend.{
  AgentResult,
  ApprovalDecision,
  AskUserChannel,
  LiveTurn,
  TurnEvent,
  DecodedTurnSpec,
  LineDecoder,
  Settled,
  Step,
  DecodedTurn,
  StreamSource
}
import orca.events.{TurnDebit, Usage}
import orca.agents.{BackendTag, Model, WireSessionId}
import orca.tools.opencode.OpencodeApi.{
  AssistantInfo,
  PermissionReply,
  PermissionReplyBody,
  PermissionRequest,
  QuestionReplyBody,
  QuestionRequest
}

import ox.Ox

import scala.util.control.NonFatal

/** Decodes one OpenCode turn off its `GET /event` SSE stream (ADR 0014): SSE
  * frame → [[OpencodeEvent]] → `TurnEvent`, deriving the [[AgentResult]] from
  * the assistant `message.updated` at `session.idle`. The SSE stream stays open
  * after a turn; the settle closes it.
  *
  * `session` is the server-allocated `ses_…` this turn runs in; the firehose
  * carries other sessions, so every event is filtered to it. Replies to
  * `ask_user`/permission go back over HTTP via [[http]], not the stream.
  */
private[opencode] final class OpencodeDecoder(
    http: OpencodeHttp,
    session: String,
    outputSchema: Option[String]
) extends LineDecoder[BackendTag.Opencode.type, OpencodeDecoder.State]:

  import OpencodeDecoder.State

  private type Out = Step[BackendTag.Opencode.type, State]

  def backendName: String = "opencode"

  def terminalMessageNoun: String = "a session.idle event"

  def init: State = State(
    text = Vector.empty,
    info = None,
    startedTools = Set.empty,
    reasoningParts = Set.empty
  )

  /** Best-effort `POST /session/{id}/abort`, so a turn that ended unsettled —
    * cancelled, or its stream lost — stops running (and writing) on the shared
    * server. A settled, idle session may be resumed next turn, so it is left
    * alone.
    */
  override def onUnsettledEnd(): Unit =
    try
      val _ = http.postJson(s"/session/$session/abort", "{}")
    catch case NonFatal(_) => ()

  def line(state: State, rawLine: String): Out =
    sseData(rawLine)
      .map(OpencodeEvent.parse)
      // Drop other sessions' frames.
      .filter(forThisSession)
      .fold(Step.continue(state))(translate(state, _))

  /** The JSON payload of one SSE line, or `None` for blank / comment / framing
    * lines (`event:`, `id:`, heartbeat `:`).
    */
  private def sseData(line: String): Option[String] =
    if line.startsWith("data:") then
      val payload = line.stripPrefix("data:").trim
      Option.when(payload.nonEmpty)(payload)
    else None

  private def forThisSession(event: OpencodeEvent): Boolean =
    event.sessionId.forall(_ == session)

  private def translate(state: State, event: OpencodeEvent): Out = event match
    case OpencodeEvent.TextDelta(_, partId, delta) =>
      // A reasoning part's deltas also arrive with `field:"text"`, so route by
      // the part opencode announced rather than by the field name — otherwise a
      // reasoning model's chain of thought renders as the assistant's message.
      // A delta with no id can't be matched against an announced part, so it
      // stays assistant text.
      if partId.exists(state.reasoningParts.contains) then
        Step.continue(state, TurnEvent.AssistantThinkingDelta(delta))
      else
        Step.continue(
          state.copy(text = state.text :+ delta),
          TurnEvent.AssistantTextDelta(delta)
        )
    case OpencodeEvent.ReasoningDelta(_, delta) =>
      Step.continue(state, TurnEvent.AssistantThinkingDelta(delta))
    case OpencodeEvent.ReasoningPart(_, partId) =>
      // A part with no id records nothing, so its deltas render as assistant
      // text.
      Step.continue(
        partId.fold(state)(id =>
          state.copy(reasoningParts = state.reasoningParts + id)
        )
      )
    case OpencodeEvent.ToolStarted(_, _, tool, _)
        if isStructuredOutputEcho(tool) =>
      Step.continue(state)
    case OpencodeEvent.ToolStarted(_, partId, tool, input) =>
      val call = TurnEvent.AssistantToolCall(tool, input)
      // A tool part repeats `running` frames; surface the call once per part,
      // keyed by its id. A part with no id (protocol drift) can't be deduped
      // against — surface every frame rather than risk a coerced "" key
      // wrongly colliding two distinct id-less parts (BB5).
      partId match
        case Some(id) if state.startedTools.contains(id) => Step.continue(state)
        case Some(id) =>
          Step.continue(
            state.copy(startedTools = state.startedTools + id),
            call
          )
        case None => Step.continue(state, call)
    case OpencodeEvent.ToolFinished(_, _, tool, ok, output) =>
      if isStructuredOutputEcho(tool) then Step.continue(state)
      else
        Step.continue(
          state,
          TurnEvent.ToolResult(Some(tool), ok, output)
        )
    case OpencodeEvent.MessageUpdated(_, info) =>
      Step.continue(state.copy(info = Some(info)))
    case OpencodeEvent.QuestionAsked(req) =>
      Step.continue(
        state,
        TurnEvent.UserQuestion(questionText(req), replyToQuestion(req))
      )
    case OpencodeEvent.PermissionAsked(req) =>
      Step.continue(
        state,
        TurnEvent.ApproveTool(
          req.permission,
          req.patterns.mkString(" "),
          replyToPermission(req)
        )
      )
    case OpencodeEvent.Idle(_)             => finishTurn(state)
    case OpencodeEvent.Errored(_, message) => failTurn(state, message)
    case OpencodeEvent.Ignored             => Step.continue(state)

  /** The server-injected structured-output call: its payload already reaches
    * the caller through the result, so rendering the tool exchange would show
    * the same JSON twice. Gated on the schema, so a user tool of the same name
    * still renders on a plain run.
    */
  private def isStructuredOutputEcho(tool: String): Boolean =
    outputSchema.isDefined && tool == OpencodeBackend.StructuredOutputToolName

  /** Terminal (`session.idle`): a turn whose assistant message carries
    * `info.error`, or that went idle without producing anything, is a failure;
    * otherwise settle with the built result.
    */
  private def finishTurn(state: State): Out =
    state.info.flatMap(_.error) match
      case Some(err) => failTurn(state, OpencodeEvent.errorMessage(err))
      case None =>
        if state.info.isEmpty && state.text.isEmpty then
          failTurn(state, "session went idle without an assistant message")
        else settleResult(state)

  private def failTurn(state: State, message: String): Out =
    Step.Settle(state, Nil, Settled.Failed(message, failedTurnDebit(state)))

  /** Settle the turn with the synthesised result: in structured mode the
    * validated object, otherwise the accrued assistant text. Usage and model
    * come from the captured `info`.
    */
  private def settleResult(state: State): Out =
    val info = state.info
    val structured = info.flatMap(_.structured).map(_.value)
    Step.Settle(
      state,
      Nil,
      Settled.Succeeded(
        AgentResult[BackendTag.Opencode.type](
          WireSessionId(session),
          structured.getOrElse(state.text.mkString),
          settledUsage(info),
          info.flatMap(_.modelID).map(Model.apply)
        )
      )
    )

  /** What a COMPLETED turn reports. Unlike [[failedTurnDebit]] there is no
    * "nothing measured" case to represent: every completed turn owes a
    * `UnpricedTurn`, since the cost log keeps one line per turn and its `turn`
    * index counts them. A message that carried no `tokens` settles at zero —
    * still carrying any cost opencode reported alongside them.
    */
  private def settledUsage(info: Option[AssistantInfo]): Usage =
    info
      .flatMap(usageOf)
      .getOrElse(Usage.empty.copy(cost = info.flatMap(_.cost)))

  /** The assistant message is refreshed by every `message.updated` frame, so a
    * turn that errors part-way still carries whatever it had spent by then.
    * Keyed on the token counts, not on the message: an assistant message that
    * arrived without any is nothing measured, and an all-zero `UnpricedTurn`
    * would read as a measured zero.
    */
  def failedTurnDebit(state: State): TurnDebit =
    state.info
      .flatMap(info => usageOf(info).map((_, info.modelID)))
      .fold(TurnDebit.Unobserved): (usage, modelID) =>
        TurnDebit.Observed(usage, modelID.map(Model.apply))

  private def usageOf(info: AssistantInfo): Option[Usage] =
    info.tokens.map: tokens =>
      Usage(
        freshInputTokens = tokens.input,
        cacheReadInputTokens = tokens.cache.read,
        cacheWriteInputTokens = tokens.cache.write,
        outputTokens = tokens.output,
        reasoningOutputTokens = tokens.reasoning,
        cost = info.cost,
        apiCalls = None
      )

  private def questionText(req: QuestionRequest): String =
    req.questions.headOption.map(_.question).getOrElse("")

  private def replyToQuestion(req: QuestionRequest)(answer: String): Unit =
    val _ = http.postJson(
      s"/question/${req.id}/reply",
      writeToString(QuestionReplyBody(List(List(answer))))
    )

  private def replyToPermission(
      req: PermissionRequest
  )(decision: ApprovalDecision): Unit =
    val verdict = decision match
      case ApprovalDecision.Allow => PermissionReply.Once
      case ApprovalDecision.Deny  => PermissionReply.Reject
    val _ = http.postJson(
      s"/permission/${req.id}/reply",
      writeToString(PermissionReplyBody(verdict))
    )

private[opencode] object OpencodeDecoder:

  final case class State(
      text: Vector[String],
      info: Option[AssistantInfo],
      startedTools: Set[String],
      reasoningParts: Set[String]
  )

private[opencode] object OpencodeTurn:

  /** Starts decoding `source` into the caller's turn scope. */
  def apply(
      source: StreamSource,
      http: OpencodeHttp,
      session: String,
      outputSchema: Option[String],
      askUser: AskUserChannel,
      openingPrompt: Option[String] = None
  )(using Ox): LiveTurn[BackendTag.Opencode.type] =
    DecodedTurn.start(
      source,
      DecodedTurnSpec(
        openingPrompt = openingPrompt,
        outputSchema = outputSchema,
        structuredOutputMode = OpencodeBackend.StructuredOutputDelivery,
        askUser = askUser
      ),
      OpencodeDecoder(http, session, outputSchema)
    )
