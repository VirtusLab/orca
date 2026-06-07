package orca.tools.gemini.jsonl

import orca.events.Usage
import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** One event parsed off gemini's stdout when it runs with `-p <prompt>
  * --output-format stream-json`.
  *
  * The shape is documented in
  * [[../../../adr/0015-gemini-stream-json-driver.md ADR 0015]]; each variant
  * carries only the fields the driver actually inspects. Unknown top-level
  * types collapse to [[Unknown]] so protocol drift doesn't crash the pipeline,
  * and every wire field is optional with a default so a renamed/missing key
  * degrades gracefully rather than throwing.
  */
private[gemini] enum InboundEvent:
  /** First event in a session — carries the session id (used to drive
    * `--resume`) and, when present, the resolved model id.
    */
  case Init(sessionId: String, model: Option[String])

  /** A user or assistant message chunk. `role` is surfaced verbatim (gemini
    * spells the assistant side `model`/`assistant` depending on version); the
    * conversation decides which roles count as agent output.
    */
  case Message(role: String, content: String)
  case ToolUse(toolName: String, toolId: String, parameters: String)
  case ToolResult(toolId: String, status: String, output: String)
  case Error(message: String)

  /** Final event — aggregated token stats (mapped to [[Usage]]) and the
    * terminal status string.
    */
  case Result(usage: Usage, status: String)
  case Unknown(rawType: String)

private[gemini] object InboundEvent:

  /** Parse one JSONL line. Malformed JSON propagates `JsonReaderException` —
    * callers decide whether to skip or fail.
    */
  def parse(line: String): InboundEvent =
    val envelope = readFromString[TopEnvelope](line)
    envelope.`type` match
      case "init"        => parseInit(line)
      case "message"     => parseMessage(line)
      case "tool_use"    => parseToolUse(line)
      case "tool_result" => parseToolResult(line)
      case "error"       => parseError(line)
      case "result"      => parseResult(line)
      case other         => Unknown(other)

  private def parseInit(line: String): InboundEvent =
    val w = readFromString[InitWire](line)
    Init(w.session_id.getOrElse(""), w.model)

  private def parseMessage(line: String): InboundEvent =
    val w = readFromString[MessageWire](line)
    Message(w.role.getOrElse(""), w.content.getOrElse(""))

  private def parseToolUse(line: String): InboundEvent =
    val w = readFromString[ToolUseWire](line)
    ToolUse(
      toolName = w.tool_name.getOrElse(""),
      toolId = w.tool_id.getOrElse(""),
      parameters = w.parameters.map(_.value).getOrElse("{}")
    )

  private def parseToolResult(line: String): InboundEvent =
    val w = readFromString[ToolResultWire](line)
    ToolResult(
      toolId = w.tool_id.getOrElse(""),
      status = w.status.getOrElse(""),
      output = w.output.getOrElse("")
    )

  private def parseError(line: String): InboundEvent =
    val w = readFromString[ErrorWire](line)
    Error(w.message.getOrElse(""))

  private def parseResult(line: String): InboundEvent =
    val w = readFromString[ResultWire](line)
    val s = w.stats.getOrElse(StatsWire())
    Result(
      Usage(
        inputTokens = s.input_tokens.getOrElse(0L),
        outputTokens = s.output_tokens.getOrElse(0L),
        // gemini doesn't emit cost on the wire; cache sub-count is `cached`
        // (older/forward shapes may use `cached_input_tokens`).
        cost = None,
        cachedInputTokens = s.cached.orElse(s.cached_input_tokens).getOrElse(0L)
      ),
      status = w.status.getOrElse("")
    )

  // --- Wire shapes ---

  private case class TopEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class InitWire(
      session_id: Option[String] = None,
      model: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class MessageWire(
      role: Option[String] = None,
      content: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class ToolUseWire(
      tool_name: Option[String] = None,
      tool_id: Option[String] = None,
      parameters: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec

  private case class ToolResultWire(
      tool_id: Option[String] = None,
      status: Option[String] = None,
      output: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class ErrorWire(message: Option[String] = None)
      derives ConfiguredJsonValueCodec

  private case class StatsWire(
      input_tokens: Option[Long] = None,
      output_tokens: Option[Long] = None,
      cached: Option[Long] = None,
      cached_input_tokens: Option[Long] = None,
      total_tokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec

  private case class ResultWire(
      status: Option[String] = None,
      stats: Option[StatsWire] = None
  ) derives ConfiguredJsonValueCodec
