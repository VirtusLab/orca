package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}

import scala.util.matching.Regex

object ResponseParser:

  private val FencePattern: Regex =
    """(?s)\A```(?:\w+)?\n?(.*?)\n?```\z""".r

  /** Parse an LLM-returned JSON string into `O`, tolerating markdown code
    * fences (optionally with a language tag) that chat-tuned models sometimes
    * wrap output in. Throws on malformed JSON.
    */
  def parse[O](raw: String)(using JsonValueCodec[O]): O =
    readFromString(stripFences(raw))

  private def stripFences(raw: String): String =
    raw.trim match
      case FencePattern(inner) => inner.trim
      case unfenced            => unfenced
