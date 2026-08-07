package orca.tools.claude.streamjson

import orca.events.{Usage}
import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** One message parsed off of claude's stdout when running with `--output-format
  * stream-json --verbose --include-partial-messages`. Each variant carries only
  * the fields the driver actually inspects; the rest of the JSON is dropped.
  * Unknown top-level types collapse to [[Unknown]] so protocol drift doesn't
  * crash the pipeline.
  */
private[claude] enum InboundMessage:
  /** First message of a session — claude announces the resolved session id and
    * (typically) the model id here. The `model` is a defensive fallback for the
    * [[Result]] message's own `model` field; when both are present they agree.
    */
  case SystemInit(sessionId: String, model: Option[String])

  /** `messageId` is the model response this content came from. The CLI splits
    * one response across several `assistant` messages (prose, then each tool
    * call), all repeating the same id — so counting DISTINCT ids counts model
    * responses, which is what [[orca.events.Usage.apiCalls]] wants. `None` if
    * the CLI ever omits it, which would make the count an undercount, so the
    * driver keeps it optional rather than substituting a placeholder.
    */
  case AssistantTurn(content: List[ContentBlock], messageId: Option[String])
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
      usage: Option[Usage],
      isError: Boolean,
      model: Option[String]
  )
  case ControlRequest(requestId: String, body: ControlRequestBody)
  case StreamEvent(payload: StreamEventPayload)
  case Unknown(rawType: String)

private[claude] object InboundMessage:

  /** Parse one NDJSON line. Malformed JSON propagates `JsonReaderException` —
    * callers decide whether to skip or fail.
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
    if wire.subtype == "init" then
      SystemInit(wire.session_id.getOrElse(""), wire.model)
    else Unknown(s"system.${wire.subtype}")

  private def parseAssistant(line: String): InboundMessage =
    val wire = readFromString[MessageWire](line)
    AssistantTurn(wire.message.toBlocks, wire.message.id)

  private def parseUser(line: String): InboundMessage =
    val wire = readFromString[MessageWire](line)
    UserTurn(wire.message.toBlocks)

  private def parseResult(line: String): InboundMessage =
    val wire = readFromString[ResultWire](line)
    // The result message's own `num_turns` looks like a call count and is not
    // one: measured against claude 2.1.220 it is (tool calls + 1), so a
    // response issuing three tool calls at once reports three turns where one
    // request was made. The count comes from the distinct `assistant` message
    // ids instead — see [[ClaudeConversation]].
    Result(
      subtype = wire.subtype,
      sessionId = wire.session_id,
      output = wire.result,
      structuredOutput = wire.structured_output.map(_.value),
      usage = wire.usage.map: u =>
        Usage(
          freshInputTokens = u.input_tokens.getOrElse(0L),
          cacheReadInputTokens = u.cache_read_input_tokens.getOrElse(0L),
          // The wire's "cache creation" is orca's cache write.
          cacheWriteInputTokens = u.cache_creation_input_tokens.getOrElse(0L),
          outputTokens = u.output_tokens.getOrElse(0L),
          reasoningOutputTokens = 0L,
          cost = wire.total_cost_usd,
          apiCalls = None
        ),
      isError = wire.is_error.getOrElse(false),
      model = wire.model
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
      session_id: Option[String] = None,
      model: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class InnerMessage(
      content: List[RawJson] = Nil,
      id: Option[String] = None
  ) derives ConfiguredJsonValueCodec:
    def toBlocks: List[ContentBlock] =
      content.map(b => ContentBlock.parse(b.value))

  private case class MessageWire(message: InnerMessage)
      derives ConfiguredJsonValueCodec

  private case class UsageWire(
      input_tokens: Option[Long] = None,
      output_tokens: Option[Long] = None,
      cache_creation_input_tokens: Option[Long] = None,
      cache_read_input_tokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec

  private case class ResultWire(
      subtype: String,
      session_id: String,
      result: Option[String] = None,
      structured_output: Option[RawJson] = None,
      usage: Option[UsageWire] = None,
      total_cost_usd: Option[BigDecimal] = None,
      is_error: Option[Boolean] = None,
      model: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class ControlRequestWire(request_id: String, request: RawJson)
      derives ConfiguredJsonValueCodec

  private case class StreamEventWire(event: RawJson)
      derives ConfiguredJsonValueCodec
