package orca.shell.ui

import org.jline.terminal.Terminal

/** One selectable row in a [[ShellUi.select]] menu. `disabledReason`, when
  * set, renders the row with its reason but excludes it from selection —
  * ConsoleUI's single-choice list prompt has no non-selectable-item support
  * in its public API (only the checkbox prompt's items do), so both UI
  * backends implement disabling the same way: label-only, re-prompt on pick.
  */
case class Choice[A](
    value: A,
    label: String,
    description: Option[String] = None,
    disabledReason: Option[String] = None
)

/** Result of a single prompt: either a value, or [[UiOutcome.Cancelled]] for
  * ESC / Ctrl-C / EOF — the caller's convention is "back to the previous
  * menu", never a program exit.
  */
enum UiOutcome[+A]:
  case Selected(value: A)
  case Cancelled

/** The shell's prompt surface (ADR 0021 §3): select menus, confirmations and
  * free-text input. Two implementations share this contract: [[ConsoleUiShell]]
  * (arrow-key menus, tty only) and [[NumberedUi]] (`readLine` fallback,
  * required because ConsoleUI NPEs on non-tty stdin).
  */
trait ShellUi:
  def select[A](title: String, choices: List[Choice[A]], preselect: Option[A] = None): UiOutcome[A]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean]
  def input(prompt: String, default: Option[String] = None): UiOutcome[String]

object ShellUi:

  /** ConsoleUI when stdin+stdout are a tty, [[NumberedUi]] otherwise (ConsoleUI
    * NPEs on non-tty stdin — research 03 skeptic).
    */
  def make(terminal: Terminal): ShellUi =
    if terminal.getType != Terminal.TYPE_DUMB && System.console() != null then ConsoleUiShell(terminal)
    else NumberedUi(java.io.BufferedReader(java.io.InputStreamReader(System.in)), System.out)
