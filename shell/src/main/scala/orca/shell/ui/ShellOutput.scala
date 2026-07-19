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

  /** A plain shell-voice line: outcomes, hints, notices. */
  def info(msg: String): Unit = println(s"$Glyph $msg")

  /** A shell-voice failure, painted red. */
  def error(msg: String): Unit =
    println(fansi.Color.Red(s"$Glyph $msg").render)

  /** The run-delineation form — `◆ ── <msg> ──` — for flow-run start/end
    * markers.
    */
  def section(msg: String): Unit = println(s"$Glyph ── $msg ──")
