package orca.shell

import org.jline.terminal.TerminalBuilder
import orca.shell.ui.{ShellUi, UiOutcome}

import scala.annotation.tailrec

/** Entry point for the `orca` shell executable (ADR 0021). */
object Main:

  def main(args: Array[String]): Unit =
    println(s"orca shell ${ShellVersion.value}")
    val terminal = TerminalBuilder.builder().system(true).dumb(true).build()
    try
      val ui = ShellUi.make(terminal)
      loop(ui)
    finally terminal.close()

  /** Runs the main menu until Exit is chosen or the top-level prompt is
    * cancelled (Ctrl-C / EOF); every non-Exit item is a stub until its epic
    * lands. Continue a session is hardcoded disabled until session tracking
    * (ADR 0021 §8) lands and can report whether any session actually exists.
    */
  @tailrec private def loop(ui: ShellUi): Unit =
    val continueDisabledReason = Some("no sessions recorded yet")
    ui.select("orca shell", MainMenu.choices(continueDisabledReason)) match
      case UiOutcome.Cancelled           => ()
      case UiOutcome.Selected(MenuItem.Exit) => ()
      case UiOutcome.Selected(item) =>
        println(s"$item: not implemented yet")
        loop(ui)
