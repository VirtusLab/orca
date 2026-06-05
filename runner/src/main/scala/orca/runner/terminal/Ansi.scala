package orca.runner.terminal

/** Single source of truth for the "ANSI optional" decision in the renderer
  * layer. Each renderer carries a `useColor` boolean it chose at construction
  * time (or auto-detected); this helper strips inbound terminal controls and
  * applies the fansi attrs only when colour is on.
  *
  * Package-private — callers outside `orca.runner.terminal` have no reason to
  * reach into our colour decision.
  */
private[terminal] object Ansi:

  private val Esc: Char = 0x1b.toChar
  private val Bel: Char = 0x07.toChar
  private val Csi: Char = 0x9b.toChar
  private val Osc: Char = 0x9d.toChar
  private val Dcs: Char = 0x90.toChar
  private val Sos: Char = 0x98.toChar
  private val Pm: Char = 0x9e.toChar
  private val Apc: Char = 0x9f.toChar

  def paint(useColor: Boolean, attr: fansi.Attrs, text: String): String =
    val stripped = stripControlSequences(text)
    if useColor then attr(stripped).render else stripped

  /** Remove terminal control sequences from user/backend supplied text before
    * applying Orca's own styling. `fansi.Attrs.apply(String)` parses existing
    * ANSI and throws on unsupported sequences (for example cursor/status
    * controls such as `ESC[?25l` or OSC hyperlinks). These bytes can arrive
    * from subprocess stderr or model/tool output, so strip them instead of
    * letting renderer decoration abort the flow.
    */
  private[terminal] def stripControlSequences(text: String): String =
    val out = new StringBuilder(text.length)
    var i = 0
    while i < text.length do
      text.charAt(i) match
        case Esc                     => i = skipEsc(text, i)
        case Csi                     => i = skipCsi(text, i + 1)
        case Osc                     => i = skipStringControl(text, i + 1)
        case Dcs | Sos | Pm | Apc    => i = skipStringControl(text, i + 1)
        case c if isUnsafeControl(c) => i += 1
        case c =>
          val _ = out.append(c)
          i += 1
    out.toString

  private def skipEsc(text: String, escIndex: Int): Int =
    val nextIndex = escIndex + 1
    if nextIndex >= text.length then text.length
    else
      text.charAt(nextIndex) match
        case '['                   => skipCsi(text, nextIndex + 1)
        case ']'                   => skipStringControl(text, nextIndex + 1)
        case 'P' | 'X' | '^' | '_' => skipStringControl(text, nextIndex + 1)
        case _                     => math.min(nextIndex + 1, text.length)

  private def skipCsi(text: String, start: Int): Int =
    var i = start
    while i < text.length && !isCsiFinal(text.charAt(i)) do i += 1
    if i < text.length then i + 1 else text.length

  private def skipStringControl(text: String, start: Int): Int =
    var i = start
    var done = false
    while i < text.length && !done do
      text.charAt(i) match
        case Bel =>
          i += 1
          done = true
        case Esc if i + 1 < text.length && text.charAt(i + 1) == '\\' =>
          i += 2
          done = true
        case _ => i += 1
    i

  private def isCsiFinal(c: Char): Boolean = c >= '@' && c <= '~'

  private def isUnsafeControl(c: Char): Boolean =
    (c < ' ' && c != '\n' && c != '\t') || (c >= 0x7f.toChar && c <= Apc)
