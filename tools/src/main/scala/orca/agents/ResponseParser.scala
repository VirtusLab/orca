package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  JsonValueCodec,
  readFromString
}

import scala.annotation.tailrec
import scala.util.matching.Regex

/** Thrown when the agent returned output that doesn't parse as `O`. Carries the
  * raw (possibly truncated) response and a short human-readable cause message;
  * the underlying jsoniter exception (with its hex buffer dump) is attached as
  * `getCause` for `--verbose` inspection without being part of the default
  * message.
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
    * fences (optionally with a language tag) and prose preamble/coda around the
    * JSON body. Tries candidates from the *right* first, so a final answer
    * `{...}` at the end of the response wins over an incidental `{ ... }` in
    * prose (e.g. a Java snippet quoted in the explanation). On a total parse
    * failure, raises a [[MalformedAgentOutputException]] carrying the raw
    * output and a short cause — never jsoniter's hex buffer dump.
    */
  def parse[O](raw: String)(using JsonValueCodec[O]): O =
    val trimmed = stripFences(raw)
    // `trimmed` is always in the candidate list so every failure has
    // at least one recorded JsonReaderException to surface.
    val candidates = (extractJsonObjects(trimmed).reverse :+ trimmed).distinct
    val attempts = candidates.map(tryParse[O])
    attempts.collectFirst { case Right(value) => value } match
      case Some(value) => value
      case None =>
        val lastError = attempts.collect { case Left(e) => e }.last
        throw new MalformedAgentOutputException(
          rawOutput = raw,
          shortCause = shortMessage(lastError),
          cause = lastError
        )

  private def tryParse[O: JsonValueCodec](
      candidate: String
  ): Either[JsonReaderException, O] =
    try Right(readFromString[O](candidate))
    catch case e: JsonReaderException => Left(e)

  private def stripFences(raw: String): String =
    raw.trim match
      case FencePattern(inner) => inner.trim
      case unfenced            => unfenced

  /** Every balanced `{...}` substring in the input, in source order. The parser
    * tries these right-to-left so an incidental code snippet in prose doesn't
    * preempt the real final answer that agents tend to place at the end of a
    * response.
    */
  private def extractJsonObjects(s: String): List[String] =
    @tailrec
    def loop(i: Int, acc: List[String]): List[String] =
      if i >= s.length then acc.reverse
      else if s.charAt(i) != '{' then loop(i + 1, acc)
      else
        matchingCloseBrace(s, i) match
          case Some(end) => loop(end + 1, s.substring(i, end + 1) :: acc)
          case None      => loop(i + 1, acc)
    loop(0, Nil)

  /** Cursor over the in-string escape state while scanning for a matching `}`.
    * Tracking it explicitly keeps the scan tail-recursive.
    */
  private enum ScanMode:
    case Normal, InString, Escaped

  private def matchingCloseBrace(s: String, open: Int): Option[Int] =
    @tailrec
    def loop(i: Int, depth: Int, mode: ScanMode): Option[Int] =
      if i >= s.length then None
      else
        val ch = s.charAt(i)
        mode match
          case ScanMode.Escaped =>
            loop(i + 1, depth, ScanMode.InString)
          case ScanMode.InString =>
            val next =
              if ch == '\\' then ScanMode.Escaped
              else if ch == '"' then ScanMode.Normal
              else ScanMode.InString
            loop(i + 1, depth, next)
          case ScanMode.Normal =>
            ch match
              case '"' => loop(i + 1, depth, ScanMode.InString)
              case '{' => loop(i + 1, depth + 1, ScanMode.Normal)
              case '}' =>
                if depth == 1 then Some(i)
                else loop(i + 1, depth - 1, ScanMode.Normal)
              case _ => loop(i + 1, depth, ScanMode.Normal)
    loop(open, 0, ScanMode.Normal)

  /** jsoniter's default `getMessage` bundles the offset + a hex buffer dump —
    * useful in logs, intimidating in a user-facing error. Keep only the first
    * line.
    */
  private def shortMessage(e: JsonReaderException): String =
    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    msg.linesIterator.nextOption().getOrElse(msg)
