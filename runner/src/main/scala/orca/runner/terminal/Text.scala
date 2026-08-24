package orca.runner.terminal

/** Tiny string helpers for the terminal-rendering layer. Truncation is
  * character-based and doesn't account for wide graphemes.
  */
private[terminal] object Text:

  /** Pre-compiled — `String.replaceAll` recompiles on every call, and this
    * fires once per rendered event (many per turn on a busy session).
    */
  private val WhitespaceRun: java.util.regex.Pattern =
    java.util.regex.Pattern.compile("\\s+")

  /** Cut `s` to at most `max` characters; if it overflowed, replace the last
    * visible character with an ellipsis. The returned string is therefore at
    * most `max` characters long.
    */
  def truncate(s: String, max: Int): String =
    if s.length <= max then s
    else s"${s.take(max - 1)}…"

  /** Collapse all runs of whitespace to a single space and trim. Use before
    * spending a width budget: an embedded newline or tab costs a character the
    * reader never sees, and breaks the single-line discipline.
    */
  def collapseWhitespace(s: String): String =
    WhitespaceRun.matcher(s).replaceAll(" ").trim

  /** [[collapseWhitespace]], then truncate to `max`. */
  def oneLine(s: String, max: Int): String =
    truncate(collapseWhitespace(s), max)

  /** Prefix `text` with `indent`, re-indenting every embedded newline so a
    * multi-line block stays aligned under the leading glyph/indent.
    */
  def indentBlock(indent: String, text: String): String =
    indent + text.replace("\n", "\n" + indent)
