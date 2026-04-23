package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** A single block inside an assistant or user message's `content` array.
  *
  * Claude Code emits several block shapes; we model the ones the driver
  * actually routes on. Unknown block types collapse to `Unknown(rawType)`
  * so protocol drift doesn't crash the parser.
  */
private[claude] enum ContentBlock:
  case Text(text: String)
  case Thinking(text: String)
  case ToolUse(id: String, name: String, rawInput: String)
  case ToolResult(toolUseId: String, content: String, isError: Boolean)
  case Unknown(rawType: String)

private[claude] object ContentBlock:

  def parse(rawJson: String): ContentBlock =
    val envelope = readFromString[BlockEnvelope](rawJson)
    envelope.`type` match
      case "text"        => readFromString[TextWire](rawJson).toBlock
      case "thinking"    => readFromString[ThinkingWire](rawJson).toBlock
      case "tool_use"    => readFromString[ToolUseWire](rawJson).toBlock
      case "tool_result" => readFromString[ToolResultWire](rawJson).toBlock
      case other         => Unknown(other)

  // --- Wire-level shapes (jsoniter-derived; kept private). ---

  private case class BlockEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class TextWire(text: String)
      derives ConfiguredJsonValueCodec:
    def toBlock: ContentBlock = Text(text)

  private case class ThinkingWire(thinking: String)
      derives ConfiguredJsonValueCodec:
    def toBlock: ContentBlock = Thinking(thinking)

  private case class ToolUseWire(
      id: String,
      name: String,
      input: RawJson
  ) derives ConfiguredJsonValueCodec:
    def toBlock: ContentBlock =
      ToolUse(id = id, name = name, rawInput = input.value)

  private case class ToolResultWire(
      tool_use_id: String,
      content: String = "",
      is_error: Option[Boolean] = None
  ) derives ConfiguredJsonValueCodec:
    def toBlock: ContentBlock =
      ToolResult(
        toolUseId = tool_use_id,
        content = content,
        isError = is_error.getOrElse(false)
      )
