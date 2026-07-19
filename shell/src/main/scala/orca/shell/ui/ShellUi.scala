package orca.shell.ui

import org.jline.terminal.Terminal

/** Result of a single prompt: either a value, or [[UiOutcome.Cancelled]] for
  * ESC / Ctrl-C / EOF — the caller's convention is "back out of the current
  * prompt"; at the top-level menu, [[orca.shell.Main]] treats that as the
  * signal to end the shell.
  */
enum UiOutcome[+A]:
  case Selected(value: A)
  case Cancelled

  def map[B](f: A => B): UiOutcome[B] = this match
    case Selected(value) => Selected(f(value))
    case Cancelled       => Cancelled

  def flatMap[B](f: A => UiOutcome[B]): UiOutcome[B] = this match
    case Selected(value) => f(value)
    case Cancelled       => Cancelled

/** The shell's prompt surface (ADR 0021 §3): select menus, confirmations and
  * free-text input. Two implementations share this contract: [[ConsoleUiShell]]
  * (arrow-key menus, tty only) and [[NumberedUi]] (`readLine` fallback,
  * required because ConsoleUI NPEs on non-tty stdin).
  */
trait ShellUi:
  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean]
  def input(prompt: String, default: Option[String] = None): UiOutcome[String]

  /** Multi-line free-text input, for prompts whose answer is often more than
    * one line (currently just the flow task text): prints `$prompt:` on its own
    * line followed by [[ShellUi.multilineHint]], then reads lines until EOF
    * (Ctrl-D), returning them joined with `\n` and trimmed. Each pasted line
    * lands as one iteration, so a blank line pasted mid-text survives in the
    * result.
    */
  def inputMultiline(prompt: String): UiOutcome[String]

object ShellUi:

  /** Shown under the prompt line by both [[inputMultiline]] backends. */
  private[ui] val multilineHint =
    "(paste freely — multiple lines ok; finish with Ctrl-D, Ctrl-C cancels)"

  /** Whether stdin+stdout are both a real tty — the gate [[make]] uses to pick
    * a backend, also reused by callers that need to know before they print
    * (e.g. `FlowViewer`'s highlight-or-plain choice).
    */
  def isInteractive(terminal: Terminal): Boolean =
    terminal.getType != Terminal.TYPE_DUMB && System.console() != null

  /** ConsoleUI when stdin+stdout are a tty, [[NumberedUi]] otherwise (ConsoleUI
    * NPEs on non-tty stdin — research 03 skeptic).
    */
  def make(terminal: Terminal): ShellUi =
    if isInteractive(terminal) then ConsoleUiShell(terminal)
    else
      NumberedUi(
        java.io.BufferedReader(java.io.InputStreamReader(System.in)),
        System.out
      )
