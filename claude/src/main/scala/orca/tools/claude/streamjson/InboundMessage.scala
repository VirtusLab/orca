package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import orca.Usage

/** One message parsed off of claude's stdout when running with `--output-format
  * stream-json --verbose --include-partial-messages`. Each variant carries only
  * the fields the driver actually inspects; the rest of the JSON is dropped.
  * Unknown top-level types collapse to [[Unknown]] so protocol drift doesn't
  * crash the pipeline.
  */
private[claude] enum InboundMessage:
  case SystemInit(sessionId: String)
  case AssistantTurn(content: List[ContentBlock])
  case UserTurn(content: List[ContentBlock])

  /** Final turn result. When the session ran with `--json-schema`, the
    * validated value lands in `structuredOutput` as raw JSON; without the flag
    * (or in error cases) the agent's free-form reply lands in `output`. Callers
    * that need a single value should prefer `structuredOutput.orElse(output)`.
    */
  case Result(
      subtype: String,
      sessionId: String,
      output: Option[String],
      structuredOutput: Option[String],
      usage: Usage,
      isError: Boolean
  )
  case ControlRequest(requestId: String, body: ControlRequestBody)
  case StreamEvent(payload: StreamEventPayload)
  case Unknown(rawType: String)

private[claude] object InboundMessage:

  /** Parse one NDJSON line. The dispatch reads the `type` field, then re-reads
    * the line into the appropriate case class. Malformed JSON propagates
    * `JsonReaderException` — callers decide whether to skip or fail.
    */
  def parse(line: String): InboundMessage =
    val envelope = readFromString[TopEnvelope](line)
    envelope.`type` match
      case "system"          => parseSystem(line)
      case "assistant"       => parseAssistant(line)
      case "user"            => parseUser(line)
      case "result"          => parseResult(line)
      case "control_request" => parseControlRequest(line)
      case "stream_event"    => parseStreamEvent(line)
      case other             => Unknown(other)

  private def parseSystem(line: String): InboundMessage =
    val wire = readFromString[SystemWire](line)
    if wire.subtype == "init" then SystemInit(wire.session_id.getOrElse(""))
    else Unknown(s"system.${wire.subtype}")

  private def parseAssistant(line: String): InboundMessage =
    val wire = readFromString[MessageWire](line)
    AssistantTurn(wire.message.toBlocks)

  private def parseUser(line: String): InboundMessage =
    val wire = readFromString[MessageWire](line)
    UserTurn(wire.message.toBlocks)

  private def parseResult(line: String): InboundMessage =
    val wire = readFromString[ResultWire](line)
    Result(
      subtype = wire.subtype,
      sessionId = wire.session_id,
      output = wire.result,
      structuredOutput = wire.structured_output.map(_.value),
      usage = Usage(
        inputTokens = wire.usage.flatMap(_.input_tokens).getOrElse(0L),
        outputTokens = wire.usage.flatMap(_.output_tokens).getOrElse(0L),
        cost = wire.total_cost_usd
      ),
      isError = wire.is_error.getOrElse(false)
    )

  private def parseControlRequest(line: String): InboundMessage =
    val wire = readFromString[ControlRequestWire](line)
    ControlRequest(
      requestId = wire.request_id,
      body = ControlRequestBody.parse(wire.request.value)
    )

  private def parseStreamEvent(line: String): InboundMessage =
    val wire = readFromString[StreamEventWire](line)
    StreamEvent(StreamEventPayload.parse(wire.event.value))

  // --- Wire shapes ---

  private case class TopEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class SystemWire(
      subtype: String,
      session_id: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class InnerMessage(content: List[RawJson] = Nil)
      derives ConfiguredJsonValueCodec:
    def toBlocks: List[ContentBlock] =
      content.map(b => ContentBlock.parse(b.value))

  private case class MessageWire(message: InnerMessage)
      derives ConfiguredJsonValueCodec

  private case class UsageWire(
      input_tokens: Option[Long] = None,
      output_tokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec

  private case class ResultWire(
      subtype: String,
      session_id: String,
      result: Option[String] = None,
      structured_output: Option[RawJson] = None,
      usage: Option[UsageWire] = None,
      total_cost_usd: Option[BigDecimal] = None,
      is_error: Option[Boolean] = None
  ) derives ConfiguredJsonValueCodec

  private case class ControlRequestWire(request_id: String, request: RawJson)
      derives ConfiguredJsonValueCodec

  private case class StreamEventWire(event: RawJson)
      derives ConfiguredJsonValueCodec
