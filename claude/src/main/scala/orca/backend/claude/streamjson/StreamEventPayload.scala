package orca.backend.claude.streamjson

import orca.json.{JsonCodec, RawJson}

/** The inner `event` of a `stream_event` message: one chunk of a partial
  * message. We route on the block-delta events (text, thinking, tool-input
  * chunks) that drive responsive UI. Events we recognise but don't act on
  * collapse to `Unhandled(eventType)`; this is deliberately distinct from
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
    val envelope = JsonCodec.readString[EventEnvelope](rawJson)
    envelope.`type` match
      case "content_block_delta" => parseBlockDelta(rawJson)
      case "content_block_start" => parseBlockStart(rawJson)
      case "content_block_stop"  =>
        ContentBlockStop(JsonCodec.readString[BlockStopWire](rawJson).index)
      case other => Unhandled(other)

  private def parseBlockDelta(rawJson: String): StreamEventPayload =
    val outer = JsonCodec.readString[BlockDeltaOuter](rawJson)
    val delta = JsonCodec.readString[DeltaEnvelope](outer.delta.value)
    delta.`type` match
      case "text_delta" =>
        TextDelta(
          outer.index,
          JsonCodec.readString[TextDeltaWire](outer.delta.value).text
        )
      case "thinking_delta" =>
        ThinkingDelta(
          outer.index,
          JsonCodec.readString[ThinkingDeltaWire](outer.delta.value).thinking
        )
      case "input_json_delta" =>
        InputJsonDelta(
          outer.index,
          JsonCodec
            .readString[InputJsonDeltaWire](outer.delta.value)
            .partial_json
        )
      case other =>
        Unhandled(s"content_block_delta.$other")

  private def parseBlockStart(rawJson: String): StreamEventPayload =
    val outer = JsonCodec.readString[BlockStartWire](rawJson)
    ContentBlockStart(
      outer.index,
      ContentBlock.parse(outer.content_block.value)
    )

  // --- Wire shapes ---

  private case class EventEnvelope(`type`: String) derives JsonCodec

  private case class BlockDeltaOuter(index: Int, delta: RawJson)
      derives JsonCodec

  private case class DeltaEnvelope(`type`: String) derives JsonCodec

  private case class TextDeltaWire(text: String) derives JsonCodec

  private case class ThinkingDeltaWire(thinking: String) derives JsonCodec

  private case class InputJsonDeltaWire(partial_json: String) derives JsonCodec

  private case class BlockStartWire(index: Int, content_block: RawJson)
      derives JsonCodec

  private case class BlockStopWire(index: Int) derives JsonCodec
