package orca.claude

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.OrcaEvent

// Envelope types for Claude Code's `stream-json` events. Only the fields the
// orchestrator currently consumes are modeled; unknown fields are dropped per
// jsoniter's default `skipUnexpectedFields = true`.
private[claude] case class StreamContentBlock(
    `type`: String,
    text: Option[String] = None
) derives ConfiguredJsonValueCodec

private[claude] case class StreamMessage(
    content: List[StreamContentBlock] = Nil
) derives ConfiguredJsonValueCodec

private[claude] case class StreamEnvelope(
    `type`: String,
    message: Option[StreamMessage] = None
) derives ConfiguredJsonValueCodec

object ClaudeNdjsonParser:

  /** Parse an iterator of NDJSON lines lazily into OrcaEvents. Currently emits
    * `LlmOutput` for each text block on every `assistant` message; other event
    * types are ignored. Malformed JSON lines propagate JsonReaderException —
    * callers wanting resilience should wrap accordingly.
    */
  def parseLines(lines: Iterator[String]): Iterator[OrcaEvent] =
    lines.filter(_.nonEmpty).flatMap(parseLine)

  /** Convenience overload for canned / test input. Forces evaluation. */
  def parse(ndjson: String): List[OrcaEvent] =
    parseLines(ndjson.linesIterator).toList

  private def parseLine(line: String): List[OrcaEvent] =
    val envelope = readFromString[StreamEnvelope](line)
    envelope.`type` match
      case "assistant" =>
        for
          message <- envelope.message.toList
          block <- message.content
          if block.`type` == "text"
          text <- block.text.toList
        yield OrcaEvent.LlmOutput(text)
      case _ => Nil
