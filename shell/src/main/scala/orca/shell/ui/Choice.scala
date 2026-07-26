package orca.shell.ui

/** One selectable row in a [[ShellUi.select]] menu. `disabledReason`, when set,
  * renders the row with its reason but excludes it from selection — ConsoleUI's
  * single-choice list prompt has no non-selectable-item support in its public
  * API (only the checkbox prompt's items do), so both UI backends implement
  * disabling the same way: label-only, re-prompt on pick.
  */
case class Choice[A](
    value: A,
    label: String,
    disabledReason: Option[String] = None
):
  def isEnabled: Boolean = disabledReason.isEmpty

  /** `label`, with the disabled reason folded in as an "(unavailable: ...)"
    * suffix; identical wording on both UI backends.
    */
  def renderedLabel: String =
    disabledReason.fold(label)(reason => s"$label (unavailable: $reason)")

  /** What both UI backends print when the user picks this choice despite it
    * being disabled, before re-prompting — the explained-refusal that replaces
    * a silent re-render. Only meaningful when `!isEnabled`; falls back to a
    * generic message if called without a reason set.
    */
  def disabledSelectionMessage: String =
    disabledReason.fold(s"'$label' is unavailable.")(reason =>
      s"'$label' is unavailable: $reason"
    )
