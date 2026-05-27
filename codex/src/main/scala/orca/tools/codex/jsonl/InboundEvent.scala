package orca.tools.codex.jsonl

import orca.events.{Usage}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** One event parsed off codex's stdout when it runs with `exec --json`.
  *
  * The shape is documented in
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]]; each variant
  * carries only the fields the driver actually inspects. Unknown top-level
  * types collapse to [[Unknown]] so protocol drift doesn't crash the pipeline.
  */
private[codex] enum InboundEvent:
  /** First event in a session — carries the thread / session id and (when codex
    * includes it on the wire) the resolved model id.
    */
  case ThreadStarted(threadId: String, model: Option[String])
  case TurnStarted
  case TurnCompleted(usage: Usage)
  case ItemStarted(item: Item)
  case ItemCompleted(item: Item)
  case Unknown(rawType: String)

/** A unit of work codex announces during a turn. `agent_message` is the model's
  * prose; the rest are tool calls of various flavours. Items that don't match a
  * modelled shape collapse to [[Other]].
  */
private[codex] enum Item:
  case AgentMessage(id: String, text: String)
  case Reasoning(id: String, text: String)
  case CommandExecution(
      id: String,
      command: String,
      output: String,
      exitCode: Option[Int],
      status: String
  )
  case FileChange(id: String, changes: List[FileChangeDetail], status: String)
  /** Codex's MCP tool-call item. `server` is the configured MCP server name
    * (from `mcp_servers.<server>.url` in the config); `tool` is the bare slug
    * the server advertised. `arguments` is the raw JSON the model supplied;
    * `result` is the rendered text from the MCP tool result's `content`
    * array, or `None` while the call is in-flight.
    */
  case McpToolCall(
      id: String,
      server: String,
      tool: String,
      arguments: String,
      result: Option[String],
      status: String
  )
  case Other(itemType: String, id: String)

private[codex] case class FileChangeDetail(path: String, kind: String)

private[codex] object InboundEvent:

  /** Parse one JSONL line. Malformed JSON propagates `JsonReaderException` —
    * callers decide whether to skip or fail.
    */
  def parse(line: String): InboundEvent =
    val envelope = readFromString[TopEnvelope](line)
    envelope.`type` match
      case "thread.started" => parseThreadStarted(line)
      case "turn.started"   => TurnStarted
      case "turn.completed" => parseTurnCompleted(line)
      case "item.started"   => ItemStarted(parseItem(line))
      case "item.completed" => ItemCompleted(parseItem(line))
      case other            => Unknown(other)

  private def parseThreadStarted(line: String): InboundEvent =
    val wire = readFromString[ThreadStartedWire](line)
    ThreadStarted(wire.thread_id, wire.model)

  private def parseTurnCompleted(line: String): InboundEvent =
    val wire = readFromString[TurnCompletedWire](line)
    val u = wire.usage.getOrElse(UsageWire())
    TurnCompleted(
      Usage(
        // In codex's wire shape `input_tokens` is the total billed input
        // (cached + non-cached), and `output_tokens` is the total output
        // (visible + reasoning) — `cached_input_tokens` and
        // `reasoning_output_tokens` are sub-breakdowns. Keep both axes.
        inputTokens = u.input_tokens.getOrElse(0L),
        cachedInputTokens = u.cached_input_tokens.getOrElse(0L),
        outputTokens = u.output_tokens.getOrElse(0L),
        reasoningOutputTokens = u.reasoning_output_tokens.getOrElse(0L),
        // codex doesn't emit cost in the JSONL stream; left None.
        cost = None
      )
    )

  private def parseItem(line: String): Item =
    val wire = readFromString[ItemWire](line)
    val item = wire.item
    item.`type` match
      case "agent_message" =>
        Item.AgentMessage(item.id, item.text.getOrElse(""))
      case "reasoning" =>
        Item.Reasoning(item.id, item.text.getOrElse(""))
      case "command_execution" =>
        Item.CommandExecution(
          id = item.id,
          command = item.command.getOrElse(""),
          output = item.aggregated_output.getOrElse(""),
          exitCode = item.exit_code,
          status = item.status.getOrElse("")
        )
      case "file_change" =>
        Item.FileChange(
          id = item.id,
          changes = item.changes
            .getOrElse(Nil)
            .map(c => FileChangeDetail(c.path, c.kind)),
          status = item.status.getOrElse("")
        )
      case "mcp_tool_call" =>
        Item.McpToolCall(
          id = item.id,
          server = item.server.getOrElse(""),
          tool = item.tool.getOrElse(""),
          arguments = item.arguments.map(_.value).getOrElse("{}"),
          // Item.started carries `result: null`; only item.completed has a
          // real value. Treat absence/null as "in-flight" → None.
          result = item.result.flatMap(renderMcpResultText),
          status = item.status.getOrElse("")
        )
      case other =>
        Item.Other(other, item.id)

  /** Flatten an MCP `result` object's `content` array into a displayable
    * string. The standard MCP result shape is
    * `{"content":[{"type":"text","text":"…"}, …],"isError":bool}`. We pull
    * out the text fragments and join them; non-text fragments are dropped.
    * Returns `None` for an empty result (e.g. item.started's null) so the
    * caller can distinguish "no result yet" from "empty result".
    */
  private def renderMcpResultText(raw: RawJson): Option[String] =
    val trimmed = raw.value.trim
    if trimmed == "null" || trimmed.isEmpty then None
    else
      try
        val wire = readFromString[McpResultWire](trimmed)
        val joined = wire.content
          .flatMap(c => c.text.filter(_ => c.`type` == "text"))
          .mkString
        Some(joined)
      catch
        case _: Throwable =>
          // Result shapes vary across MCP servers; if parsing fails, surface
          // the raw JSON rather than dropping the diagnostic.
          Some(trimmed)

  // --- Wire shapes ---

  private case class TopEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class ThreadStartedWire(
      thread_id: String,
      model: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class UsageWire(
      input_tokens: Option[Long] = None,
      cached_input_tokens: Option[Long] = None,
      output_tokens: Option[Long] = None,
      reasoning_output_tokens: Option[Long] = None
  ) derives ConfiguredJsonValueCodec

  private case class TurnCompletedWire(usage: Option[UsageWire] = None)
      derives ConfiguredJsonValueCodec

  private case class FileChangeWire(path: String, kind: String)
      derives ConfiguredJsonValueCodec

  private case class ItemBody(
      id: String,
      `type`: String,
      text: Option[String] = None,
      command: Option[String] = None,
      aggregated_output: Option[String] = None,
      exit_code: Option[Int] = None,
      status: Option[String] = None,
      changes: Option[List[FileChangeWire]] = None,
      server: Option[String] = None,
      tool: Option[String] = None,
      arguments: Option[RawJson] = None,
      result: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec

  private case class ItemWire(item: ItemBody) derives ConfiguredJsonValueCodec

  /** Subset of the MCP `tools/call` result we extract for display — just the
    * `content` array. `isError` is read off the item's top-level `status`
    * field by codex itself, so we don't need it here.
    */
  private case class McpResultWire(
      content: List[McpContentWire] = Nil
  ) derives ConfiguredJsonValueCodec

  private case class McpContentWire(
      `type`: String,
      text: Option[String] = None
  ) derives ConfiguredJsonValueCodec
