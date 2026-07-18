package orca.shell

import org.jline.terminal.TerminalBuilder
import orca.settings.GlobalSettings
import orca.shell.ui.{ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, Wizard}
import orca.subprocess.PathProbe
import ox.discard

import scala.annotation.tailrec

/** Entry point for the `orca` shell executable (ADR 0021). */
object Main:

  def main(args: Array[String]): Unit =
    println(s"orca shell ${ShellVersion.value}")
    val terminal = TerminalBuilder.builder().system(true).dumb(true).build()
    try
      val ui = ShellUi.make(terminal)
      val globalSettingsPath = GlobalSettings.default
      val wizard = Wizard(ui, PathProbe.resolves(_, os.pwd), globalSettingsPath)
      runWizardIfFirstRun(ui, wizard, globalSettingsPath)
      loop(ui, wizard)
    finally terminal.close()

  /** Runs the welcome wizard before the first menu when [[FirstRun.check]]
    * says this is a first run (ADR 0021 §4). A malformed global file is NOT
    * first-run: it's surfaced with a repair offer instead — declining leaves
    * the file untouched and skips the wizard, so every flow run keeps
    * failing loudly on it until it's fixed by hand or via Re-configure.
    */
  private def runWizardIfFirstRun(ui: ShellUi, wizard: Wizard, globalSettingsPath: os.Path): Unit =
    FirstRun.check(globalSettingsPath) match
      case Right(true)  => wizard.run(reconfigure = false).discard
      case Right(false) => ()
      case Left(error) =>
        println(s"orca: the global settings file is malformed — $error")
        ui.confirm("Rewrite it from scratch with the wizard?", default = false) match
          case UiOutcome.Selected(true) =>
            os.remove(globalSettingsPath).discard
            wizard.run(reconfigure = false).discard
          case _ => ()

  /** Runs the main menu until Exit is chosen or the top-level prompt is
    * cancelled (Ctrl-C / EOF); every non-Exit, non-Reconfigure item is a stub
    * until its epic lands. Continue a session is hardcoded disabled until
    * session tracking (ADR 0021 §8) lands and can report whether any session
    * actually exists.
    */
  @tailrec private def loop(ui: ShellUi, wizard: Wizard): Unit =
    val continueDisabledReason = Some("no sessions recorded yet")
    ui.select("orca shell", MainMenu.choices(continueDisabledReason)) match
      case UiOutcome.Cancelled                      => ()
      case UiOutcome.Selected(MenuItem.Exit)        => ()
      case UiOutcome.Selected(MenuItem.Reconfigure) =>
        wizard.run(reconfigure = true).discard
        loop(ui, wizard)
      case UiOutcome.Selected(item) =>
        println(s"$item: not implemented yet")
        loop(ui, wizard)
