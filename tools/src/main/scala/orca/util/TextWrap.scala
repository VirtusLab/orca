package orca.util

/** Word-wrapping helper for multi-line event-log messages (review comments
  * etc.), with a hanging indent on continuation lines.
  *
  * Wrapping happens at flow time, not render time, so a single channel-agnostic
  * width keeps output predictable across terminal / Slack / HTTP; channels
  * wanting a different width post-process. The default 76 columns leaves room
  * for the `▶ ` glyph at typical stage-depth indents.
  */
private[orca] object TextWrap:

  /** Wrap `s` to `maxWidth` characters, breaking at whitespace. Continuation
    * lines are prefixed with `continuation`. Existing `\n`s are respected —
    * each pre-existing line wraps independently.
    *
    *   - Pure whitespace input collapses to "".
    *   - A single token longer than `maxWidth` overflows on its own line; words
    *     are never broken.
    */
  def wrap(
      s: String,
      maxWidth: Int = 76,
      continuation: String = "  "
  ): String =
    s.linesIterator
      .map(line => wrapOne(line, maxWidth, continuation))
      .mkString("\n")

  private def wrapOne(
      line: String,
      maxWidth: Int,
      continuation: String
  ): String =
    // Preserve leading whitespace on the first line — `split("\\s+")` would
    // drop it, collapsing intentional source indents.
    val (leading, rest) = line.span(_.isWhitespace)
    val tokens = rest.split("\\s+").filter(_.nonEmpty)
    if tokens.isEmpty then ""
    else
      val out = new StringBuilder(leading)
      var col = leading.length
      var first = true
      for token <- tokens do
        if first then
          val _ = out.append(token)
          col += token.length
          first = false
        else
          val needed = 1 + token.length // space + token
          if col + needed > maxWidth then
            val _ = out.append('\n').append(continuation).append(token)
            col = continuation.length + token.length
          else
            val _ = out.append(' ').append(token)
            col += needed
      out.toString
