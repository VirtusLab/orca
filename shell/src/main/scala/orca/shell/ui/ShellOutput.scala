package orca.shell.ui

/** The shell's own voice (ADR 0021 §3): every message the shell prints on its
  * own behalf — outside of [[ConsoleUiShell]]/[[NumberedUi]]'s `? ` prompts and
  * outside of a flow child's own output — carries this one glyph, distinct from
  * the runner's glyph family (`⏺`/`●`/`▶`/`▸`, see
  * `orca.runner.terminal.TerminalEventListener`) so shell voice is never
  * mistaken for flow-runtime output.
  */
object ShellOutput:

  private val Glyph = "◆"

  /** ANSI erase-line (`ESC[2K`) plus carriage return. `print`ed right before a
    * prompt or the banner paints, to wipe late progress bytes another writer
    * left on the current line — scala-cli/coursier's dependency-download
    * progress renderer emits a bare `\r` (no line clear) before handing control
    * to the shell, so the cursor can sit mid-line over stale text otherwise.
    */
  val AnsiClearLine = "[2K\r"

  /** ANSI erase-from-cursor-to-end-of-screen (`ESC[0J`). scala-cli/coursier's
    * download progress can render several lines at once, and its last redraw
    * leaves the cursor at the TOP of that block without erasing it —
    * [[AnsiClearLine]] only wipes the current line, so the lines below survive
    * underneath whatever paints next. `print`ed once, right after the banner
    * and only on a real tty, so the first menu/wizard paint starts on a blank
    * area instead of leaving stale tails where its (shorter) lines don't fully
    * cover the old ones. Not reused mid-session: any later stale byte is
    * single-line ([[AnsiClearLine]]'s case), and clearing to end-of-screen
    * there would also erase a flow's own output.
    */
  val AnsiClearBelow = "[0J"

  /** A plain shell-voice line: outcomes, hints, notices. */
  def info(msg: String): Unit = println(s"$Glyph $msg")

  /** A shell-voice failure, painted red. */
  def error(msg: String): Unit =
    println(fansi.Color.Red(s"$Glyph $msg").render)

  /** The run-delineation form — `◆ ── <msg> ──` — for flow-run start/end
    * markers.
    */
  def section(msg: String): Unit = println(s"$Glyph ── $msg ──")
