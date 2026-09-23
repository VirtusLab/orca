package orca.shell.menu

import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}

import scala.annotation.tailrec

/** Prompts shared by the menu actions. */
private[menu] object Prompts:

  /** Multi-line input, re-prompting with `emptyError` on blank input: blank
    * text would reach an agent as a degenerate prompt.
    */
  @tailrec def nonBlankMultiline(
      ui: ShellUi,
      label: String,
      emptyError: String
  ): Option[String] =
    ui.inputMultiline(label) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(text) if text.trim.isEmpty =>
        ShellOutput.error(emptyError)
        nonBlankMultiline(ui, label, emptyError)
      case UiOutcome.Selected(text) => Some(text)
