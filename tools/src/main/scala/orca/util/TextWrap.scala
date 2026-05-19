package orca.util

/** Simple word-wrapping helper. Used to format multi-line event-log messages
  * (review comments etc.) so they fit a fixed display width with a hanging
  * indent on continuation lines, regardless of which channel renders them.
  *
  * Wrapping happens at flow time, not render time, because a single
  * channel-agnostic wrap width keeps output predictable across terminal / Slack
  * / HTTP. Channels that want a different width can post-process; the default
  * 76 columns leaves room for the `▶ ` glyph in the terminal at typical
  * stage-depth indents.
  */
object TextWrap:

  /** Wrap `s` to `maxWidth` characters, breaking at whitespace. Continuation
    * lines are prefixed with `continuation` so they sit under the first
    * character of the message rather than under any leading glyph the renderer
    * adds. Existing `\n`s in `s` are respected — each pre-existing line wraps
    * independently.
    *
    *   - Pure whitespace input collapses to "".
    *   - A single token longer than `maxWidth` is emitted on its own line and
    *     overflows; we don't break inside a word.
    *
    * Defaults are tuned for a typical 80-column terminal at moderate
    * stage-depth indent: 76 chars wide, 2-space hanging indent (the width of
    * the renderer's `▶ ` glyph + space).
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
    // Split off any leading whitespace and preserve it on the first
    // emitted line — `s.split("\\s+")` would otherwise drop it,
    // collapsing intentional indents in the source string (e.g. the
    // "  suggestion:" prefix the caller used to align under a glyph).
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
