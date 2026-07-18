package orca.runner.terminal

import orca.util.TerminalControl

/** Applies fansi attrs to renderer text: strips inbound terminal controls (via
  * [[orca.util.TerminalControl]]), then paints only when the renderer's
  * `useColor` is on.
  */
private[terminal] object Ansi:

  def paint(useColor: Boolean, attr: fansi.Attrs, text: String): String =
    val stripped = TerminalControl.stripControlSequences(text)
    if useColor then attr(stripped).render else stripped
