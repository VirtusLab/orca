package orca.backend.cursor.jsonl

import orca.events.Usage
import orca.json.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

private[cursor] enum TerminalStatus:
  case Success
  case Failure(raw: String)

private[cursor] object TerminalStatus:
  def of(subtype: Subtype, isError: Option[Boolean]): TerminalStatus =
    (subtype, isError) match
      case (Subtype.Success, Some(false) | None) => Success
      case (_, Some(true))                       => Failure("error")
      case (other, _)                            => Failure(other.raw)

  extension (s: TerminalStatus) def isSuccess: Boolean = s == Success

private[cursor] enum EventType:
  case System, User, Assistant, ToolCall, Result, Error
  case Unknown(raw: String)

private[cursor] object EventType:
  def parse(raw: String): EventType = raw match
    case "system"    => System
    case "user"      => User
    case "assistant" => Assistant
    case "tool_call" => ToolCall
    case "result"    => Result
    case "error"     => Error
    case other       => Unknown(other)

private[cursor] enum Subtype(val raw: String):
  case Init extends Subtype("init")
  case Started extends Subtype("started")
  case Completed extends Subtype("completed")
  case Success extends Subtype("success")
  case Unknown(value: String) extends Subtype(value)

private[cursor] object Subtype:
  def parse(raw: String): Subtype = raw match
    case "init"      => Init
    case "started"   => Started
    case "completed" => Completed
    case "success"   => Success
    case other       => Unknown(other)

private[cursor] enum ContentType:
  case Text
  case Unknown(raw: String)

private[cursor] object ContentType:
  def parse(raw: String): ContentType = raw match
    case "text" => Text
    case other  => Unknown(other)

/** Boundary values for Cursor's print/stream-json protocol. Tool payloads
  * retain call_id and the entire nested tool_call object, including args and
  * result.
  */
private[cursor] enum InboundEvent:
  case Init(sessionId: String, model: Option[String])
  case AssistantText(text: String)
  case ToolStarted(name: String, payload: String)
  case ToolCompleted(name: Option[String], ok: Boolean, payload: String)
  case UserEcho
  case Error(message: String)
  case Result(
      text: Option[String],
      usage: Option[Usage],
      status: TerminalStatus
  )
  case Unknown(rawType: String)

private[cursor] object InboundEvent:

  def parse(line: String): InboundEvent =
    val envelope = readFromString[TopEnvelope](line)
    val subtype = Subtype.parse(envelope.subtype.getOrElse(""))
    EventType.parse(envelope.`type`) match
      case EventType.System =>
        subtype match
          case Subtype.Init =>
            val w = readFromString[InitWire](line)
            Init(w.session_id, w.model)
          case other => Unknown(s"system/${other.raw}")
      case EventType.User      => UserEcho
      case EventType.Assistant => parseAssistant(line)
      case EventType.ToolCall  => parseTool(line, subtype)
      case EventType.Result    => parseResult(line, subtype)
      case EventType.Error =>
        Error(readFromString[ErrorWire](line).message.getOrElse(""))
      case EventType.Unknown(raw) => Unknown(raw)

  private def parseAssistant(line: String): InboundEvent =
    val w = readFromString[AssistantWire](line)
    val text = w.message.toSeq
      .flatMap(_.content)
      .flatten
      .flatMap: block =>
        ContentType.parse(block.`type`) match
          case ContentType.Text       => block.text
          case ContentType.Unknown(_) => None
    AssistantText(text.mkString)

  private def parseTool(line: String, subtype: Subtype): InboundEvent =
    subtype match
      case Subtype.Started | Subtype.Completed =>
        val w = readFromString[ToolWire](line)
        val tool = w.tool_call.flatMap(_.headOption)
        val name = tool.map(_._1).filter(_.nonEmpty)
        if subtype == Subtype.Started then
          name.fold[InboundEvent](Unknown("tool_call/started"))(name =>
            ToolStarted(name = name, payload = line)
          )
        else
          val failed = tool.exists: (_, payload) =>
            val result = readFromString[ToolPayloadWire](payload.value).result
            result.exists: status =>
              status.error.isDefined || status.is_error.contains(true)
          ToolCompleted(name, !failed, line)
      case other => Unknown(s"tool_call/${other.raw}")

  private def parseResult(line: String, subtype: Subtype): InboundEvent =
    val w = readFromString[ResultWire](line)
    Result(
      w.result,
      w.usage.map: u =>
        Usage(
          freshInputTokens = u.inputTokens.getOrElse(0L),
          cacheReadInputTokens = u.cacheReadTokens.getOrElse(0L),
          cacheWriteInputTokens = u.cacheWriteTokens.getOrElse(0L),
          outputTokens = u.outputTokens.getOrElse(0L),
          reasoningOutputTokens = 0L,
          cost = None,
          apiCalls = None
        ),
      TerminalStatus.of(subtype, w.is_error)
    )

  private case class TopEnvelope(`type`: String, subtype: Option[String] = None)
      derives ConfiguredJsonValueCodec
  private case class InitWire(session_id: String, model: Option[String] = None)
      derives ConfiguredJsonValueCodec
  private case class TextBlockWire(`type`: String, text: Option[String] = None)
      derives ConfiguredJsonValueCodec
  private case class MessageWire(content: Option[Vector[TextBlockWire]] = None)
      derives ConfiguredJsonValueCodec
  private case class AssistantWire(message: Option[MessageWire] = None)
      derives ConfiguredJsonValueCodec
  private case class ErrorWire(message: Option[String] = None)
      derives ConfiguredJsonValueCodec
  private case class ToolWire(
      call_id: String,
      tool_call: Option[Map[String, RawJson]] = None
  ) derives ConfiguredJsonValueCodec
  private case class ToolPayloadWire(result: Option[ToolResultWire] = None)
      derives ConfiguredJsonValueCodec
  private case class ToolResultWire(
      error: Option[RawJson] = None,
      is_error: Option[Boolean] = None
  ) derives ConfiguredJsonValueCodec
  private case class UsageWire(
      inputTokens: Option[Long] = None,
      outputTokens: Option[Long] = None,
      cacheReadTokens: Option[Long] = None,
      cacheWriteTokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec
  private case class ResultWire(
      is_error: Option[Boolean] = None,
      result: Option[String] = None,
      usage: Option[UsageWire] = None
  ) derives ConfiguredJsonValueCodec
