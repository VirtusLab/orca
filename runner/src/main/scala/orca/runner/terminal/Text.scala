package orca.runner.terminal

/** Tiny string helpers for the terminal-rendering layer. Truncation is
  * character-based and doesn't account for wide graphemes.
  */
private[terminal] object Text:

  /** Cut `s` to at most `max` characters; if it overflowed, replace the last
    * visible character with an ellipsis. The returned string is therefore at
    * most `max` characters long.
    */
  def truncate(s: String, max: Int): String =
    if s.length <= max then s
    else s"${s.take(max - 1)}…"

  /** Collapse all runs of whitespace to a single space and trim, then truncate.
    * Use for places where the source string may include embedded newlines or
    * tabs (tool-result content, status labels) that would otherwise break the
    * single-line discipline.
    */
  def oneLine(s: String, max: Int): String =
    val collapsed = s.replaceAll("\\s+", " ").trim
    truncate(collapsed, max)

  /** Prefix `text` with `indent`, re-indenting every embedded newline so a
    * multi-line block stays aligned under the leading glyph/indent.
    */
  def indentBlock(indent: String, text: String): String =
    indent + text.replace("\n", "\n" + indent)
