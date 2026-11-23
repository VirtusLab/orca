package orca.backend.cursor.jsonl

import orca.events.Usage

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Outcome of a `result` event's wire `subtype` / `is_error` fields. */
private[cursor] enum TerminalStatus:
  case Success
  case Failure(raw: String)

private[cursor] object TerminalStatus:
  def of(subtype: Option[String], isError: Option[Boolean]): TerminalStatus =
    (subtype, isError) match
      case (Some("success"), Some(false) | None) => Success
      case (Some(other), _)                        => Failure(other)
      case (_, Some(true))                         => Failure("error")
      case _                                       => Failure("")

  extension (s: TerminalStatus) def isSuccess: Boolean = s == Success

/** One event parsed off `cursor-agent -p --output-format stream-json` stdout.
  * Shape matches the Cursor Agent print/stream-json interface (Composer and
  * other models). Unknown top-level types collapse to [[Unknown]].
  */
private[cursor] enum InboundEvent:
  case Init(sessionId: String, model: Option[String])
  case AssistantText(text: String)
  case UserEcho
  case Error(message: String)
  case Result(text: Option[String], usage: Usage, status: TerminalStatus)
  case Unknown(rawType: String)

private[cursor] object InboundEvent:

  def parse(line: String): InboundEvent =
    val envelope = readFromString[TopEnvelope](line)
    envelope.`type` match
      case "system" if envelope.subtype.contains("init") => parseInit(line)
      case "user"                                        => UserEcho
      case "assistant"                                   => parseAssistant(line)
      case "result"                                      => parseResult(line)
      case "error"                                       => parseError(line)
      case other =>
        Unknown(envelope.subtype.fold(other)(s => s"$other/$s"))

  private def parseInit(line: String): InboundEvent =
    val w = readFromString[InitWire](line)
    Init(w.session_id, w.model)

  private def parseAssistant(line: String): InboundEvent =
    val w = readFromString[AssistantWire](line)
    val text = w.message.toSeq
      .flatMap(_.content)
      .flatMap(_.collect { case c if c.`type` == "text" => c.text.getOrElse("") })
      .mkString
    AssistantText(text)

  private def parseError(line: String): InboundEvent =
    val w = readFromString[ErrorWire](line)
    Error(w.message.getOrElse(""))

  private def parseResult(line: String): InboundEvent =
    val w = readFromString[ResultWire](line)
    val u = w.usage.getOrElse(UsageWire())
    Result(
      w.result,
      Usage.inclusiveInput(
        totalInputTokens = u.inputTokens.getOrElse(0L),
        cacheReadInputTokens = u.cacheReadTokens.getOrElse(0L),
        cacheWriteInputTokens = u.cacheWriteTokens.getOrElse(0L),
        outputTokens = u.outputTokens.getOrElse(0L),
        reasoningOutputTokens = 0L,
        cost = None,
        apiCalls = None,
        wireTotal = None
      ),
      TerminalStatus.of(w.subtype, w.is_error)
    )

  private case class TopEnvelope(`type`: String, subtype: Option[String] = None)
      derives ConfiguredJsonValueCodec

  private case class InitWire(
      session_id: String,
      model: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class TextBlockWire(`type`: String, text: Option[String] = None)
      derives ConfiguredJsonValueCodec

  private case class MessageWire(
      content: Option[Vector[TextBlockWire]] = None
  ) derives ConfiguredJsonValueCodec

  private case class AssistantWire(message: Option[MessageWire] = None)
      derives ConfiguredJsonValueCodec

  private case class ErrorWire(message: Option[String] = None)
      derives ConfiguredJsonValueCodec

  private case class UsageWire(
      inputTokens: Option[Long] = None,
      outputTokens: Option[Long] = None,
      cacheReadTokens: Option[Long] = None,
      cacheWriteTokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec

  private case class ResultWire(
      subtype: Option[String] = None,
      is_error: Option[Boolean] = None,
      result: Option[String] = None,
      usage: Option[UsageWire] = None
  ) derives ConfiguredJsonValueCodec