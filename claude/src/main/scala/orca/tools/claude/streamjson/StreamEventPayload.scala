package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** The inner `event` of a `stream_event` message: one chunk of a partial turn.
  * We care about the block-delta events (text, thinking, tool-input chunks)
  * because they drive responsive UI. Events we recognise but don't act on
  * (message_start, message_stop, etc.) collapse to `Unhandled(eventType)` so
  * the driver has one no-op path for them; this is deliberately distinct from
  * `Unknown` in the other ADTs, which covers types we don't recognise at all.
  */
private[claude] enum StreamEventPayload:
  case TextDelta(index: Int, text: String)
  case ThinkingDelta(index: Int, text: String)
  case InputJsonDelta(index: Int, partialJson: String)
  case ContentBlockStart(index: Int, block: ContentBlock)
  case ContentBlockStop(index: Int)
  case Unhandled(eventType: String)

private[claude] object StreamEventPayload:

  /** `rawJson` is the serialized value of the outer message's `event` field. */
  def parse(rawJson: String): StreamEventPayload =
    val envelope = readFromString[EventEnvelope](rawJson)
    envelope.`type` match
      case "content_block_delta" => parseBlockDelta(rawJson)
      case "content_block_start" => parseBlockStart(rawJson)
      case "content_block_stop" =>
        ContentBlockStop(readFromString[BlockStopWire](rawJson).index)
      case other => Unhandled(other)

  private def parseBlockDelta(rawJson: String): StreamEventPayload =
    val outer = readFromString[BlockDeltaOuter](rawJson)
    val delta = readFromString[DeltaEnvelope](outer.delta.value)
    delta.`type` match
      case "text_delta" =>
        TextDelta(
          outer.index,
          readFromString[TextDeltaWire](outer.delta.value).text
        )
      case "thinking_delta" =>
        ThinkingDelta(
          outer.index,
          readFromString[ThinkingDeltaWire](outer.delta.value).thinking
        )
      case "input_json_delta" =>
        InputJsonDelta(
          outer.index,
          readFromString[InputJsonDeltaWire](outer.delta.value).partial_json
        )
      case other =>
        Unhandled(s"content_block_delta.$other")

  private def parseBlockStart(rawJson: String): StreamEventPayload =
    val outer = readFromString[BlockStartWire](rawJson)
    ContentBlockStart(
      outer.index,
      ContentBlock.parse(outer.content_block.value)
    )

  // --- Wire shapes ---

  private case class EventEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class BlockDeltaOuter(index: Int, delta: RawJson)
      derives ConfiguredJsonValueCodec

  private case class DeltaEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class TextDeltaWire(text: String)
      derives ConfiguredJsonValueCodec

  private case class ThinkingDeltaWire(thinking: String)
      derives ConfiguredJsonValueCodec

  private case class InputJsonDeltaWire(partial_json: String)
      derives ConfiguredJsonValueCodec

  private case class BlockStartWire(index: Int, content_block: RawJson)
      derives ConfiguredJsonValueCodec

  private case class BlockStopWire(index: Int) derives ConfiguredJsonValueCodec
