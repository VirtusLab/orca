package orca.runner.terminal

/** Tiny string helpers shared by the terminal-rendering layer. Scoped
  * package-private since their tradeoffs (in particular the cheap-and-
  * cheerful character truncation that doesn't worry about wide
  * graphemes) only make sense inside this layer.
  */
private[terminal] object Text:

  /** Cut `s` to at most `max` characters; if it overflowed, replace
    * the last visible character with an ellipsis. The returned string
    * is therefore at most `max` characters long.
    */
  def truncate(s: String, max: Int): String =
    if s.length <= max then s
    else s"${s.take(max - 1)}…"

  /** Collapse all runs of whitespace to a single space and trim, then
    * truncate. Use for places where the source string may include
    * embedded newlines or tabs (tool-result content, status labels)
    * that would otherwise break the single-line discipline.
    */
  def oneLine(s: String, max: Int): String =
    val collapsed = s.replaceAll("\\s+", " ").trim
    truncate(collapsed, max)
