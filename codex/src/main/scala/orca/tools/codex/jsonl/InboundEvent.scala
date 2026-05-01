package orca.tools.codex.jsonl

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import orca.Usage

/** One event parsed off codex's stdout when it runs with `exec --json`.
  *
  * The shape is documented in
  * [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]]; each variant
  * carries only the fields the driver actually inspects. Unknown top-level
  * types collapse to [[Unknown]] so protocol drift doesn't crash the pipeline.
  */
private[codex] enum InboundEvent:
  /** First event in a session — carries the thread / session id. */
  case ThreadStarted(threadId: String)
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
    ThreadStarted(wire.thread_id)

  private def parseTurnCompleted(line: String): InboundEvent =
    val wire = readFromString[TurnCompletedWire](line)
    val u = wire.usage.getOrElse(UsageWire())
    TurnCompleted(
      Usage(
        inputTokens = u.input_tokens.getOrElse(0L),
        outputTokens = u.output_tokens.getOrElse(0L),
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
      case other =>
        Item.Other(other, item.id)

  // --- Wire shapes ---

  private case class TopEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class ThreadStartedWire(thread_id: String)
      derives ConfiguredJsonValueCodec

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
      changes: Option[List[FileChangeWire]] = None
  ) derives ConfiguredJsonValueCodec

  private case class ItemWire(item: ItemBody) derives ConfiguredJsonValueCodec
