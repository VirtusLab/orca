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

  /** Cut `s` to at most `max` characters by removing its MIDDLE: head, an
    * ellipsis, then tail. For a path this keeps both ends that identify it —
    * the leading `/` that marks it as outside the working directory, and the
    * filename that [[truncate]] would eat. The whole trailing segment — its
    * leading `/` included — is kept when it fits, so the filename survives
    * intact wherever it can.
    */
  def middleTruncate(s: String, max: Int): String =
    if s.length <= max then s
    else
      val keep = max - 1 // the ellipsis costs one
      val half = keep / 2
      // Counted WITH its leading `/`, so the cut lands before the separator and
      // the result still reads as a path rather than one run-on token.
      val lastSlash = s.lastIndexOf('/')
      val lastSegment = if lastSlash < 0 then s.length else s.length - lastSlash
      // The whole trailing segment whenever something is left for the head; a
      // segment that fills the budget on its own falls back to an even split.
      val tail =
        if lastSegment < keep then math.max(lastSegment, half) else half
      s"${s.take(keep - tail)}…${s.takeRight(tail)}"

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
