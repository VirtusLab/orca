package orca.io

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  JsonValueCodec,
  readFromString
}

import scala.util.matching.Regex

/** Thrown when the agent returned output that doesn't parse as `O`. Carries
  * the raw (possibly truncated) response and a short human-readable cause
  * message; the underlying jsoniter exception (with its hex buffer dump) is
  * attached as `getCause` for `--verbose` inspection without being part of
  * the default message.
  */
class MalformedAgentOutputException(
    val rawOutput: String,
    val shortCause: String,
    cause: Throwable
) extends orca.OrcaFlowException(
      s"agent output did not parse: $shortCause"
    ):
  initCause(cause)

private[orca] object ResponseParser:

  private val FencePattern: Regex =
    """(?s)\A```(?:\w+)?\n?(.*?)\n?```\z""".r

  /** Parse an LLM-returned JSON string into `O`, tolerating markdown code
    * fences (optionally with a language tag) and prose preamble/coda
    * around the JSON body. On a parse failure, raises a
    * [[MalformedAgentOutputException]] carrying the raw output and a
    * short cause — never jsoniter's hex buffer dump.
    */
  def parse[O](raw: String)(using JsonValueCodec[O]): O =
    val trimmed = stripFences(raw)
    val candidate = extractJsonObject(trimmed).getOrElse(trimmed)
    try readFromString(candidate)
    catch
      case e: JsonReaderException =>
        throw new MalformedAgentOutputException(
          rawOutput = raw,
          shortCause = shortMessage(e),
          cause = e
        )

  private def stripFences(raw: String): String =
    raw.trim match
      case FencePattern(inner) => inner.trim
      case unfenced            => unfenced

  /** Agents occasionally wrap the JSON in prose ("Here is your plan:
    * {...}" or "{...} — done"). If the trimmed string contains a `{`
    * somewhere and the bracket-matched close ends before the end of the
    * string, extract the object substring. Returns `None` if the whole
    * string is already one object (start=0, end=last) — the caller then
    * uses the input verbatim. Returns `None` if no balanced object is
    * found — caller falls back so the original parse error surfaces.
    */
  private def extractJsonObject(s: String): Option[String] =
    val start = s.indexOf('{')
    if start < 0 then None
    else
      matchingCloseBrace(s, start).flatMap { end =>
        val alreadyClean = start == 0 && end == s.length - 1
        if alreadyClean then None
        else Some(s.substring(start, end + 1))
      }

  private def matchingCloseBrace(s: String, open: Int): Option[Int] =
    var depth = 0
    var i = open
    var inString = false
    var escape = false
    while i < s.length do
      val ch = s.charAt(i)
      if inString then
        if escape then escape = false
        else if ch == '\\' then escape = true
        else if ch == '"' then inString = false
      else
        ch match
          case '"' => inString = true
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if depth == 0 then return Some(i)
          case _ => ()
      i += 1
    None

  /** jsoniter's default `getMessage` bundles the offset + a hex buffer
    * dump — useful in logs, intimidating in a user-facing error. Keep
    * only the first line.
    */
  private def shortMessage(e: JsonReaderException): String =
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    msg.linesIterator.nextOption().getOrElse(msg)
