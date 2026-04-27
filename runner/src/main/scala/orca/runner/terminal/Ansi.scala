package orca.runner.terminal

/** Single source of truth for the "ANSI optional" decision in the
  * renderer layer. Each renderer carries a `useColor` boolean it
  * chose at construction time (or auto-detected); this helper applies
  * the fansi attrs only when colour is on, returning the raw text
  * otherwise.
  *
  * Package-private — callers outside `orca.runner.terminal` have no
  * reason to reach into our colour decision.
  */
private[terminal] object Ansi:

  def paint(useColor: Boolean, attr: fansi.Attrs, text: String): String =
    if useColor then attr(text).render else text
