package orca.runner.terminal

import orca.util.TerminalControl

/** Single source of truth for the "ANSI optional" decision in the renderer
  * layer. Each renderer carries a `useColor` boolean it chose at construction
  * time (or auto-detected); this helper strips inbound terminal controls (via
  * the shared [[orca.util.TerminalControl]]) and applies the fansi attrs only
  * when colour is on.
  *
  * Package-private — callers outside `orca.runner.terminal` have no reason to
  * reach into our colour decision.
  */
private[terminal] object Ansi:

  def paint(useColor: Boolean, attr: fansi.Attrs, text: String): String =
    val stripped = TerminalControl.stripControlSequences(text)
    if useColor then attr(stripped).render else stripped
