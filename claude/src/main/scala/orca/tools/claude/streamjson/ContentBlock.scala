package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}
import orca.OrcaDebug

import scala.util.control.NonFatal

/** A single block inside an assistant or user message's `content` array.
  *
  * Claude Code emits several block shapes; we model the ones the driver
  * actually routes on. Unknown block types collapse to `Unknown(rawType)` so
  * protocol drift doesn't crash the parser.
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

  private case class TextWire(text: String) derives ConfiguredJsonValueCodec:
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

  /** Claude's tool_result `content` field is either a plain string or a list of
    * nested blocks (text / tool_reference / etc.). Capture it as RawJson and
    * reduce to a displayable string at the domain boundary; callers that need
    * structure can re-parse.
    */
  private case class ToolResultWire(
      tool_use_id: String,
      content: RawJson = RawJson("\"\""),
      is_error: Option[Boolean] = None
  ) derives ConfiguredJsonValueCodec:
    def toBlock: ContentBlock =
      ToolResult(
        toolUseId = tool_use_id,
        content = renderToolResultContent(content.value),
        isError = is_error.getOrElse(false)
      )

  /** Claude sends `content` as either a JSON string literal or an array of
    * nested blocks (currently: text blocks and tool_reference blocks). We
    * flatten to a human-readable string so the renderer can truncate without
    * leaking `[{"type":"text",...}]` scaffolding into the terminal. Unknown
    * shapes fall back to the raw JSON.
    */
  private def renderToolResultContent(raw: String): String =
    val trimmed = raw.trim
    if trimmed.startsWith("\"") then decodeStringLiteral(trimmed)
    else if trimmed.startsWith("[") then decodeNestedBlocks(trimmed)
    else trimmed

  private def decodeStringLiteral(raw: String): String =
    try readFromString[String](raw)(using stringCodec)
    catch
      case NonFatal(t) =>
        // The fallback to the raw payload is intentional — claude
        // occasionally sends malformed string literals and we'd rather
        // show something than crash. Surface the detail under
        // ORCA_DEBUG so a real bug isn't masked. Fatal errors (VM
        // errors, InterruptedException, etc.) propagate.
        logSwallowedDecode("decodeStringLiteral", raw, t)
        raw

  private def decodeNestedBlocks(raw: String): String =
    try
      val flat = flattenBlocks(
        readFromString[List[NestedBlock]](raw)(using nestedBlocksCodec)
      )
      if flat.nonEmpty then flat else raw
    catch
      case NonFatal(t) =>
        logSwallowedDecode("decodeNestedBlocks", raw, t)
        raw

  private def logSwallowedDecode(
      where: String,
      raw: String,
      t: Throwable
  ): Unit =
    if OrcaDebug.streamTrace || OrcaDebug.enabled then
      val snippet =
        if raw.length > 200 then s"${raw.take(200)}…" else raw
      System.err.println(
        s"[orca-debug claude.$where] swallowed ${t.getClass.getName}: " +
          s"${Option(t.getMessage).getOrElse("")} | raw=$snippet"
      )

  /** Concatenate the legible bits of each block: text prose as-is, and
    * tool_reference lists (ToolSearch results) as a comma-joined name list —
    * easier to skim than `<Name1> <Name2>`. Returns an empty string if the
    * blocks carry nothing renderable, so the caller can fall back to the raw
    * JSON.
    */
  private def flattenBlocks(blocks: List[NestedBlock]): String =
    val texts =
      blocks.flatMap(_.asText).map(_.trim).filter(_.nonEmpty).mkString(" ")
    val toolNames = blocks.flatMap(_.asToolName).mkString(", ")
    List(texts, toolNames).filter(_.nonEmpty).mkString(" ")

  private case class NestedBlock(
      `type`: String,
      text: Option[String] = None,
      tool_name: Option[String] = None
  ):
    def asText: Option[String] = text.filter(_ => `type` == "text")
    def asToolName: Option[String] =
      tool_name.filter(_ => `type` == "tool_reference")

  private given stringCodec
      : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[String] =
    JsonCodecMaker.make

  private given nestedBlocksCodec
      : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[List[
        NestedBlock
      ]] =
    JsonCodecMaker.make
