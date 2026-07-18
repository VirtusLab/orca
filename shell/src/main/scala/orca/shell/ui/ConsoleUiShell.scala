package orca.shell.ui

import org.jline.consoleui.elements.ConfirmChoice
import org.jline.consoleui.elements.PromptableElementIF
import org.jline.consoleui.prompt.{ConfirmResult, ConsolePrompt, InputResult, ListResult, PromptResultItemIF}
import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.terminal.Terminal
import ox.discard

import java.io.IOError
import scala.annotation.tailrec

/** ConsoleUI-backed [[ShellUi]]: arrow-key prompts over
  * `org.jline.consoleui.prompt.ConsolePrompt` (ADR 0021 §3). Requires a real
  * tty — construct only via `ShellUi.make`, which gates on it; ConsoleUI
  * NPEs on non-tty stdin otherwise (research 03 skeptic).
  *
  * ConsoleUI's single-choice list prompt has no non-selectable-item support
  * in its public API — `ListItem.isSelectable()` is hardcoded `true`, and
  * `ListPromptBuilder` exposes no way to add a `Separator`; only the
  * checkbox prompt's items carry a disabled flag (jar-verified against
  * `jline-console-ui` 3.30.15, correcting research 03's blanket "disabled
  * items with reason" claim). Disabled choices are therefore rendered with
  * their reason folded into the label and, if picked anyway, the prompt
  * simply re-runs — same "not a valid answer" re-prompt `NumberedUi` uses.
  * The same absence rules out honoring `preselect`'s starting cursor
  * position; it is a no-op here.
  */
private[ui] final class ConsoleUiShell(terminal: Terminal) extends ShellUi:

  // Every call below is a single-element prompt batch, so making the first
  // (only) prompt cancellable makes ESC cancel every call; ConsolePrompt
  // otherwise defaults cancellableFirstPrompt to false and ESC just
  // re-renders the same prompt forever.
  private val consolePrompt =
    val config = ConsolePrompt.UiConfig()
    config.setCancellableFirstPrompt(true)
    ConsolePrompt(terminal, config)

  def select[A](title: String, choices: List[Choice[A]], preselect: Option[A] = None): UiOutcome[A] =
    @tailrec def loop(): UiOutcome[A] =
      val builder = consolePrompt.getPromptBuilder
      val list = builder.createListPrompt().name("select").message(title)
      choices.zipWithIndex.foreach { case (choice, index) =>
        list.newItem(index.toString).text(choice.renderedLabel).add().discard
      }
      list.addPrompt().discard
      runOrCancelled(builder.build()) match
        case UiOutcome.Cancelled => UiOutcome.Cancelled
        case UiOutcome.Selected(results) =>
          val selectedId = results.get("select").asInstanceOf[ListResult].getSelectedId
          val chosen = choices(selectedId.toInt)
          if chosen.isEnabled then UiOutcome.Selected(chosen.value) else loop()

    loop()

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val defaultValue = if default then ConfirmChoice.ConfirmationValue.YES else ConfirmChoice.ConfirmationValue.NO
    val builder = consolePrompt.getPromptBuilder
    builder.createConfirmPromp().name("confirm").message(question).defaultValue(defaultValue).addPrompt().discard
    runOrCancelled(builder.build()) match
      case UiOutcome.Cancelled => UiOutcome.Cancelled
      case UiOutcome.Selected(results) =>
        val confirmed = results.get("confirm").asInstanceOf[ConfirmResult].getConfirmed
        UiOutcome.Selected(confirmed == ConfirmChoice.ConfirmationValue.YES)

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    val builder = consolePrompt.getPromptBuilder
    val inputBuilder = builder.createInputPrompt().name("input").message(prompt)
    // InputValuePrompt appends its defaultValue verbatim (even null) when the
    // user submits empty input, so an absent default must still be passed
    // explicitly as "" — otherwise an empty submit returns the literal string
    // "null".
    inputBuilder.defaultValue(default.getOrElse("")).discard
    inputBuilder.addPrompt().discard
    runOrCancelled(builder.build()) match
      case UiOutcome.Cancelled => UiOutcome.Cancelled
      case UiOutcome.Selected(results) =>
        UiOutcome.Selected(results.get("input").asInstanceOf[InputResult].getResult)

  /** Runs one prompt batch. ESC (an empty result map — ConsoleUI's own
    * cancel-to-empty-map behavior, enabled by `cancellableFirstPrompt`),
    * `UserInterruptException` (Ctrl-C), `EndOfFileException` and `IOError`
    * (both raised by jline's `BindingReader` on a severed tty) all surface as
    * [[UiOutcome.Cancelled]] (ADR 0021 §3).
    */
  private def runOrCancelled(
      elements: java.util.List[PromptableElementIF]
  ): UiOutcome[java.util.Map[String, PromptResultItemIF]] =
    try
      val results = consolePrompt.prompt(elements)
      if results.isEmpty then UiOutcome.Cancelled else UiOutcome.Selected(results)
    catch case _: UserInterruptException | _: EndOfFileException | _: IOError => UiOutcome.Cancelled
