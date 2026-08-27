package orca.runner.terminal

/** How many characters of a line's BODY are left once everything printed ahead
  * of it is paid for. The caps ([[ConversationRenderer.MaxInlineInputLength]],
  * [[TerminalEventListener.MaxAssistantMessageLength]]) bound the whole
  * rendered line, but the body is written after a stage indent, a glyph and
  * sometimes an agent name — spending the full cap on the body puts a line
  * meant to be one line onto two.
  */
private[terminal] object LineBudget:

  /** Never leave less than this, however deep the stage nesting: a line showing
    * a few characters and an ellipsis is worth more than the alignment.
    */
  val Floor: Int = 24

  /** `max` less every prefix width passed, floored at [[Floor]]. Widths are
    * measured on unpainted text — ANSI escapes take no columns.
    */
  def remaining(max: Int, prefixes: Int*): Int =
    math.max(Floor, max - prefixes.sum)

  /** Columns a `glyph ` prefix takes: the glyph plus its separating space.
    * Derived from the glyph the renderer prints rather than fixed at 2, so a
    * marker wider than one character can't silently overrun the budget.
    */
  def glyphWidth(glyph: String): Int = glyph.length + 1
