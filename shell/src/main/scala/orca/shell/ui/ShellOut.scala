package orca.shell.ui

/** The shell's own voice: every message the shell prints on its own behalf —
  * outside a prompt's `? ` marker ([[ConsoleUiShell]]/[[NumberedUi]]) and
  * outside a flow child's own output — carries this one glyph, distinct from
  * the flow-run renderer's glyph family (`⏺`/`●`/`▶`/`▸`), so shell voice is
  * never mistaken for flow-runtime output.
  */
object ShellOut:

  private val glyph = "◆"

  /** A shell-voice line: banners, notices, warnings, errors, flow start/end. */
  def say(message: String): Unit = println(s"$glyph $message")
