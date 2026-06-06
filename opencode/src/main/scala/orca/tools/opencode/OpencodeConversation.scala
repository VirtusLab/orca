package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import orca.AgentTurnFailed
import orca.backend.{
  ApprovalDecision,
  ConversationEvent,
  LlmResult,
  StreamConversation,
  StreamSource
}
import orca.events.Usage
import orca.llm.{BackendTag, Model, SessionId}
import orca.tools.opencode.OpencodeApi.{
  AssistantInfo,
  PermissionReply,
  PermissionReplyBody,
  PermissionRequest,
  QuestionReplyBody,
  QuestionRequest
}

import scala.util.control.NonFatal

/** Drives one OpenCode turn to completion off its `GET /event` SSE stream (ADR
  * 0014).
  *
  * The reader-loop / event-queue / outcome lifecycle lives in
  * [[StreamConversation]]; this class supplies the OpenCode-specific
  * translation: SSE frame → [[OpencodeEvent]] → `ConversationEvent`, deriving
  * the [[LlmResult]] from the assistant `message.updated` at `session.idle`.
  * The SSE stream stays open after a turn, so reaching the terminal interrupts
  * `source` to make the reader observe EOF.
  *
  * `session` is the server-allocated `ses_…` this conversation owns; the
  * firehose carries other sessions, so every event is filtered to it. Replies
  * to `ask_user`/permission go back over HTTP via [[http]], not the stream.
  */
private[opencode] class OpencodeConversation(
    source: StreamSource,
    http: OpencodeHttp,
    session: String,
    val outputSchema: Option[String],
    canAsk: Boolean,
    initialPrompt: String = ""
) extends StreamConversation[BackendTag.Opencode.type](
      source,
      "opencode",
      initialPrompt,
      nativeAskUser = canAsk
    ):

  /** Follow-up turns are issued as separate `runInteractive` calls (a fresh
    * `prompt_async`); mid-turn injection isn't wired. A turn paused on
    * `ask_user` resumes via the question reply, not this.
    */
  def sendUserMessage(text: String): Unit = ()

  /** Best-effort `POST /session/{id}/abort` before closing the stream, so a
    * cancelled turn stops running (and writing) on the shared server instead of
    * continuing headless after the user has moved on.
    */
  override def cancel(): Unit =
    if !cancelled.get() then
      try
        val _ = http.postJson(s"/session/$session/abort", "{}")
      catch case NonFatal(_) => ()
    super.cancel()

  /** Turn state, accumulated as the reader thread processes frames.
    * `handleLine` (and the `buildResult` it drives at `session.idle`) run only
    * on that single thread, so a plain `var` over an immutable snapshot is safe
    * and avoids cross-thread machinery; `awaitResult` reads the outcome only
    * after joining the reader, which publishes these writes.
    */
  private var turnState: TurnState = TurnState()

  private case class TurnState(
      text: Vector[String] = Vector.empty,
      info: Option[AssistantInfo] = None,
      startedTools: Set[String] = Set.empty
  )

  protected def handleLine(rawLine: String): Unit =
    sseData(rawLine).foreach: json =>
      val event = OpencodeEvent.parse(json)
      // Drop other sessions' frames; once the turn has settled, ignore the rest.
      if forThisSession(event) && outcomeRef.get().isEmpty then translate(event)

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

  private def translate(event: OpencodeEvent): Unit = event match
    case OpencodeEvent.TextDelta(_, delta) =>
      turnState = turnState.copy(text = turnState.text :+ delta)
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(delta))
    case OpencodeEvent.ReasoningDelta(_, delta) =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(delta))
    case OpencodeEvent.ToolStarted(_, partId, tool, input) =>
      // A tool part repeats `running` frames; surface the call once per part.
      if !turnState.startedTools.contains(partId) then
        turnState =
          turnState.copy(startedTools = turnState.startedTools + partId)
        eventQueue.enqueue(ConversationEvent.AssistantToolCall(tool, input))
    case OpencodeEvent.ToolFinished(_, _, tool, ok, output) =>
      eventQueue.enqueue(ConversationEvent.ToolResult(tool, ok, output))
    case OpencodeEvent.MessageUpdated(_, info) =>
      turnState = turnState.copy(info = Some(info))
    case OpencodeEvent.QuestionAsked(req) =>
      eventQueue.enqueue(
        ConversationEvent.UserQuestion(questionText(req), replyToQuestion(req))
      )
    case OpencodeEvent.PermissionAsked(req) =>
      eventQueue.enqueue(
        ConversationEvent.ApproveTool(
          req.permission,
          req.patterns.mkString(" "),
          replyToPermission(req)
        )
      )
    case OpencodeEvent.Idle(_)             => finishTurn()
    case OpencodeEvent.Errored(_, message) => failTurn(message)
    case OpencodeEvent.Ignored             => ()

  /** Terminal (`session.idle`): a turn whose assistant message carries
    * `info.error`, or that went idle without producing anything, is a failure;
    * otherwise mark turn end and settle with the built result. Both paths close
    * the otherwise open-ended SSE stream (via [[succeedWith]]/[[failWith]]).
    */
  private def finishTurn(): Unit =
    turnState.info.flatMap(_.error) match
      case Some(err) => failTurn(OpencodeEvent.errorMessage(err))
      case None =>
        if turnState.info.isEmpty && turnState.text.isEmpty then
          failTurn("session went idle without an assistant message")
        else
          eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
          succeedWith(buildResult())

  private def failTurn(message: String): Unit =
    failWith(AgentTurnFailed(message))

  /** In structured mode the validated object is the result; otherwise the
    * accrued assistant text. Usage and model come from the captured `info`.
    */
  private def buildResult(): LlmResult[BackendTag.Opencode.type] =
    val info = turnState.info
    val structured = info.flatMap(_.structured).map(_.value)
    LlmResult(
      sessionId = SessionId[BackendTag.Opencode.type](session),
      output = structured.getOrElse(turnState.text.mkString),
      usage = usageOf(info),
      model = info.flatMap(_.modelID).map(Model.apply)
    )

  private def usageOf(info: Option[AssistantInfo]): Usage =
    val tokens = info.flatMap(_.tokens)
    Usage(
      // `input` is the non-cached input; cache read/write are billed separately,
      // so the total input axis sums all three.
      inputTokens =
        tokens.map(t => t.input + t.cache.read + t.cache.write).getOrElse(0L),
      outputTokens = tokens.map(_.output).getOrElse(0L),
      cost = info.flatMap(_.cost),
      cachedInputTokens = tokens.map(_.cache.read).getOrElse(0L),
      reasoningOutputTokens = tokens.map(_.reasoning).getOrElse(0L)
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
      case ApprovalDecision.Allow(_) => PermissionReply.Once
      case ApprovalDecision.Deny(_)  => PermissionReply.Reject
    val _ = http.postJson(
      s"/permission/${req.id}/reply",
      writeToString(PermissionReplyBody(verdict))
    )

  start()
