package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.tools.opencode.OpencodeApi.{
  AssistantInfo,
  PermissionRequest,
  QuestionRequest
}
import orca.util.RawJson

/** One event parsed from the OpenCode `GET /event` SSE stream (ADR 0014).
  *
  * Each SSE frame is `{id, type, properties}`. Only the events the conversation
  * driver acts on get a variant; everything else (`server.*`, `session.status`,
  * `session.diff`, `session.next.*`, …) collapses to [[Ignored]] so the reader
  * can drop it after filtering. [[sessionId]] lifts the owning session so the
  * driver can keep only its own turn's events without re-decoding.
  */
private[opencode] enum OpencodeEvent:
  /** A `field:"text"` delta. `text` is the name of the accruing field on BOTH a
    * text and a reasoning part, so the frame alone doesn't say which — the
    * consumer decides from `partId` and the [[ReasoningPart]] it saw for it.
    */
  case TextDelta(session: String, partId: Option[String], delta: String)
  case ReasoningDelta(session: String, delta: String)

  /** A reasoning part was announced; its deltas arrive as [[TextDelta]] on this
    * `partId`. Emitted again as the part's status advances, like
    * [[ToolStarted]].
    */
  case ReasoningPart(session: Option[String], partId: Option[String])

  /** A tool part reached `running` — its full `input` is known. Emitted on
    * every `running` frame for a part, so the same `partId` can repeat as the
    * input accrues; the consumer dedups per `partId` before rendering.
    * `session`/`partId` are `None` when the frame omits them — never coerced to
    * `""`, since two id-less parts sharing a coerced `""` key would otherwise
    * wrongly dedup against each other.
    */
  case ToolStarted(
      session: Option[String],
      partId: Option[String],
      tool: String,
      input: String
  )

  /** A tool part reached `completed`/`error`. See [[ToolStarted]] for why
    * `session`/`partId` stay `Option`.
    */
  case ToolFinished(
      session: Option[String],
      partId: Option[String],
      tool: String,
      ok: Boolean,
      output: String
  )

  /** The assistant message metadata updated — the source of the `AgentResult`.
    */
  case MessageUpdated(session: String, info: AssistantInfo)
  case QuestionAsked(request: QuestionRequest)
  case PermissionAsked(request: PermissionRequest)

  /** Terminal frames. `session` is optional: a terminal that omits its id is
    * treated as belonging to this turn (see [[sessionId]] / the driver's
    * session filter) so a protocol deviation settles the turn instead of
    * hanging it.
    */
  case Idle(session: Option[String])
  case Errored(session: Option[String], message: String)
  case Ignored

  /** The session this event belongs to, when it carries one. `None` for
    * server-level frames ([[Ignored]] `server.connected`/`heartbeat`).
    */
  def sessionId: Option[String] = this match
    case TextDelta(s, _, _)          => Some(s)
    case ReasoningDelta(s, _)        => Some(s)
    case ReasoningPart(s, _)         => s
    case ToolStarted(s, _, _, _)     => s
    case ToolFinished(s, _, _, _, _) => s
    case MessageUpdated(s, _)        => Some(s)
    case QuestionAsked(r)            => Some(r.sessionID)
    case PermissionAsked(r)          => Some(r.sessionID)
    case Idle(s)                     => s
    case Errored(s, _)               => s
    case Ignored                     => None

private[opencode] object OpencodeEvent:

  /** Parse one SSE `data:` payload into an [[OpencodeEvent]]. Malformed JSON
    * propagates `JsonReaderException`; the caller decides whether to skip or
    * fail. Unknown event types and ones the driver doesn't model become
    * [[OpencodeEvent.Ignored]].
    */
  def parse(json: String): OpencodeEvent =
    readFromString[Envelope](json).`type` match
      case "message.part.delta"   => parsePartDelta(json)
      case "message.part.updated" => parsePartUpdated(json)
      case "message.updated"      => parseMessageUpdated(json)
      case "session.idle" =>
        Idle(readFromString[SessionFrame](json).properties.sessionID)
      case "session.error" => parseError(json)
      case "question.asked" =>
        QuestionAsked(readFromString[QuestionFrame](json).properties)
      case "permission.asked" =>
        PermissionAsked(readFromString[PermissionFrame](json).properties)
      case _ => Ignored

  private def parsePartDelta(json: String): OpencodeEvent =
    val p = readFromString[PartDeltaFrame](json).properties
    p.field match
      case "text"      => TextDelta(p.sessionID, p.partID, p.delta)
      case "reasoning" => ReasoningDelta(p.sessionID, p.delta)
      case _           => Ignored

  /** A tool part is reported by `message.part.updated` repeatedly as its status
    * advances; emit a start when its input is first complete (`running`) and a
    * finish when it settles. A reasoning part is announced so its
    * `field:"text"` deltas can be told apart from real assistant text; other
    * parts (text/step/…) carry no event.
    */
  private def parsePartUpdated(json: String): OpencodeEvent =
    val props = readFromString[PartUpdatedFrame](json).properties
    val part = props.part
    if part.`type` == "reasoning" then
      ReasoningPart(props.sessionID.orElse(part.sessionID), part.id)
    else if part.`type` != "tool" then Ignored
    else
      // `properties.sessionID` is the reliable session source; fall back to the
      // part's own copy on frames that omit it. Neither this nor `part.id` is
      // coerced to "" — see [[OpencodeEvent.ToolStarted]].
      val session = props.sessionID.orElse(part.sessionID)
      val tool = part.tool.getOrElse("")
      val partId = part.id
      part.state.flatMap(_.status) match
        case Some("running") =>
          ToolStarted(
            session,
            partId,
            tool,
            part.state.flatMap(_.input).map(_.value).getOrElse("{}")
          )
        case Some("completed") =>
          ToolFinished(
            session,
            partId,
            tool,
            ok = true,
            part.state.flatMap(_.output).getOrElse("")
          )
        case Some("error") =>
          ToolFinished(
            session,
            partId,
            tool,
            ok = false,
            part.state.flatMap(_.output).getOrElse("")
          )
        case _ => Ignored

  /** Only the **assistant** message carries the turn result. OpenCode also
    * fires a `message.updated` for the user echo (`role:"user"`, no
    * structured/tokens); dropping it keeps an empty echo from masquerading as
    * the result.
    */
  private def parseMessageUpdated(json: String): OpencodeEvent =
    val props = readFromString[MessageUpdatedFrame](json).properties
    val info = props.info
    if !info.role.contains("assistant") then Ignored
    else
      MessageUpdated(props.sessionID.orElse(info.sessionID).getOrElse(""), info)

  private def parseError(json: String): OpencodeEvent =
    val p = readFromString[SessionFrame](json).properties
    Errored(p.sessionID, p.error.map(errorMessage).getOrElse("session error"))

  /** The human-readable text of an error payload. OpenCode wraps errors as
    * `{name, data:{message}}` (and sometimes a bare `{message}`); fall back to
    * the raw JSON if neither field is present.
    */
  private[opencode] def errorMessage(error: RawJson): String =
    scala.util
      .Try(readFromString[ErrorBody](error.value))
      .toOption
      .flatMap(b => b.message.orElse(b.data.flatMap(_.message)))
      .filter(_.nonEmpty)
      .getOrElse(error.value)

  // --- Wire shapes (only the fields the driver inspects) ---

  private case class Envelope(`type`: String) derives ConfiguredJsonValueCodec

  private case class ToolState(
      status: Option[String] = None,
      input: Option[RawJson] = None,
      output: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class Part(
      `type`: String,
      id: Option[String] = None,
      tool: Option[String] = None,
      sessionID: Option[String] = None,
      state: Option[ToolState] = None
  ) derives ConfiguredJsonValueCodec

  private case class PartDeltaProps(
      sessionID: String,
      partID: Option[String] = None,
      field: String = "",
      delta: String = ""
  ) derives ConfiguredJsonValueCodec
  private case class PartDeltaFrame(properties: PartDeltaProps)
      derives ConfiguredJsonValueCodec

  private case class PartUpdatedProps(
      part: Part,
      sessionID: Option[String] = None
  ) derives ConfiguredJsonValueCodec
  private case class PartUpdatedFrame(properties: PartUpdatedProps)
      derives ConfiguredJsonValueCodec

  private case class MessageUpdatedProps(
      info: AssistantInfo,
      sessionID: Option[String] = None
  ) derives ConfiguredJsonValueCodec
  private case class MessageUpdatedFrame(properties: MessageUpdatedProps)
      derives ConfiguredJsonValueCodec

  private case class SessionProps(
      sessionID: Option[String] = None,
      error: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec
  private case class SessionFrame(properties: SessionProps)
      derives ConfiguredJsonValueCodec

  private case class ErrorData(message: Option[String] = None)
      derives ConfiguredJsonValueCodec
  private case class ErrorBody(
      message: Option[String] = None,
      data: Option[ErrorData] = None
  ) derives ConfiguredJsonValueCodec

  private case class QuestionFrame(properties: QuestionRequest)
      derives ConfiguredJsonValueCodec
  private case class PermissionFrame(properties: PermissionRequest)
      derives ConfiguredJsonValueCodec
