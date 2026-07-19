package orca.shell.ui

import org.jline.consoleui.elements.ConfirmChoice
import org.jline.consoleui.elements.PromptableElementIF
import org.jline.consoleui.prompt.{
  ConfirmResult,
  ConsolePrompt,
  InputResult,
  ListResult,
  PromptResultItemIF
}
import org.jline.reader.{
  EndOfFileException,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.Terminal
import org.jline.utils.{AttributedStringBuilder, AttributedStyle}
import ox.discard

import java.io.IOError
import scala.annotation.tailrec

/** ConsoleUI-backed [[ShellUi]]: arrow-key prompts over
  * `org.jline.consoleui.prompt.ConsolePrompt` (ADR 0021 §3). Requires a real
  * tty — construct only via `ShellUi.make`, which gates on it; ConsoleUI NPEs
  * on non-tty stdin otherwise (research 03 skeptic).
  *
  * ConsoleUI's single-choice list prompt has no non-selectable-item support in
  * its public API — `ListItem.isSelectable()` is hardcoded `true`, and
  * `ListPromptBuilder` exposes no way to add a `Separator`; only the checkbox
  * prompt's items carry a disabled flag (jar-verified against
  * `jline-console-ui` 3.30.15, correcting research 03's blanket "disabled items
  * with reason" claim). Disabled choices are therefore rendered with their
  * reason folded into the label and, if picked anyway, the prompt simply
  * re-runs — same "not a valid answer" re-prompt `NumberedUi` uses. The same
  * absence rules out honoring `preselect`'s starting cursor position; it is a
  * no-op here.
  *
  * A fresh `ConsolePrompt` is built for every top-level `select`/`confirm`/
  * `input` call rather than reused across the shell's lifetime: `ConsolePrompt`
  * owns a `Display` that tracks the terminal cursor purely by bookkeeping (its
  * own prior renders), with no way to re-sync it to wherever the cursor
  * actually is. Sharing one `Display` across independent calls left that
  * bookkeeping stale the moment anything else touched the terminal — including
  * a plain `println` between prompts, or even just the previous call's own
  * closing single-line render — so the next prompt silently overwrote the
  * previous prompt's already-finalized answer line instead of drawing below it
  * (reproduced live: a completed prompt's echoed answer vanished under the next
  * prompt's message). A new `ConsolePrompt` starts with an empty render
  * history, so its first `Display.update` correctly treats wherever the real
  * cursor already sits as its own starting point.
  */
private[ui] final class ConsoleUiShell(terminal: Terminal) extends ShellUi:

  private val lineReader =
    LineReaderBuilder.builder().terminal(terminal).build()

  private def newConsolePrompt(): ConsolePrompt =
    val config = ConsolePrompt.UiConfig()
    // Every call below is a single-element prompt batch, so making the first
    // (only) prompt cancellable makes ESC cancel every call; ConsolePrompt
    // otherwise defaults cancellableFirstPrompt to false and ESC just
    // re-renders the same prompt forever.
    config.setCancellableFirstPrompt(true)
    ConsolePrompt(terminal, config)

  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    val consolePrompt = newConsolePrompt()
    // ConsoleUI's post-answer summary line prints the item's id verbatim
    // (`ListResult.getDisplayResult` falls back to `getResult`, i.e. the
    // selected id), not its displayed text — an id of `index.toString` echoed
    // as e.g. "? Review agent 0" instead of the chosen label. Using the
    // rendered label itself as the id makes that echo human-readable; dedupe
    // defensively since ids must be unique (labels are unique in practice for
    // every menu/flow/harness list this renders today).
    val ids = ConsoleUiShell.uniqueIds(choices.map(_.renderedLabel))
    @tailrec def loop(): UiOutcome[A] =
      val builder = consolePrompt.getPromptBuilder
      val list = builder.createListPrompt().name("select").message(title)
      choices.zip(ids).foreach { case (choice, id) =>
        list.newItem(id).text(choice.renderedLabel).add().discard
      }
      list.addPrompt().discard
      runOrCancelled(consolePrompt, builder.build()) match
        case UiOutcome.Cancelled => UiOutcome.Cancelled
        case UiOutcome.Selected(results) =>
          val selectedId =
            results.get("select").asInstanceOf[ListResult].getSelectedId
          val chosen = choices(ids.indexOf(selectedId))
          if chosen.isEnabled then UiOutcome.Selected(chosen.value) else loop()

    loop()

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val consolePrompt = newConsolePrompt()
    val defaultValue =
      if default then ConfirmChoice.ConfirmationValue.YES
      else ConfirmChoice.ConfirmationValue.NO
    val builder = consolePrompt.getPromptBuilder
    builder
      .createConfirmPromp()
      .name("confirm")
      .message(question)
      .defaultValue(defaultValue)
      .addPrompt()
      .discard
    runOrCancelled(consolePrompt, builder.build()) match
      case UiOutcome.Cancelled => UiOutcome.Cancelled
      case UiOutcome.Selected(results) =>
        val confirmed =
          results.get("confirm").asInstanceOf[ConfirmResult].getConfirmed
        UiOutcome.Selected(confirmed == ConfirmChoice.ConfirmationValue.YES)

  /** With a default, ConsoleUI's own input prompt is used (its `(<default>)`
    * hint is useful). Without one, ConsoleUI is bypassed entirely:
    * `InputValuePrompt.execute()` appends its `defaultValue` field verbatim
    * (even `null`, via `StringBuilder.append` — jar-verified) when the user
    * submits empty input, so passing a non-null placeholder like `""` to avoid
    * that "null" bug is what previously produced the empty `(<default>)` → `()`
    * hint (`ConsolePrompt.promptElement` prints `"(" + defaultValue + ") "`
    * whenever `getDefaultValue != null`). Reading the line with a plain
    * `LineReader` on the same terminal sidesteps both bugs at once.
    */
  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    default match
      case None => plainLineInput(prompt)
      case Some(text) =>
        val consolePrompt = newConsolePrompt()
        val builder = consolePrompt.getPromptBuilder
        val inputBuilder =
          builder.createInputPrompt().name("input").message(prompt)
        inputBuilder.defaultValue(text).discard
        inputBuilder.addPrompt().discard
        runOrCancelled(consolePrompt, builder.build()) match
          case UiOutcome.Cancelled => UiOutcome.Cancelled
          case UiOutcome.Selected(results) =>
            UiOutcome.Selected(
              results.get("input").asInstanceOf[InputResult].getResult
            )

  /** `? <prompt> ` styled to match ConsoleUI's own message line (green `?`,
    * bold message — `ConsolePrompt.UiConfig`'s default `pr`/`me` colors).
    */
  private def plainLineInput(prompt: String): UiOutcome[String] =
    // Same late-byte guard as runOrCancelled: this path bypasses ConsoleUI
    // entirely, so it needs its own clear immediately before painting.
    print("[2K\r")
    val styled = AttributedStringBuilder()
      .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
      .append("? ")
      .style(AttributedStyle.BOLD)
      .append(prompt)
      .append(" ")
      .style(AttributedStyle.DEFAULT)
      .toAnsi(terminal)
    try UiOutcome.Selected(lineReader.readLine(styled))
    catch
      case _: UserInterruptException | _: EndOfFileException | _: IOError =>
        UiOutcome.Cancelled

  /** Prints the prompt line and [[ShellUi.multilineHint]] (dim, `faint()`),
    * then reads lines off the same `lineReader` used by [[plainLineInput]]
    * until Ctrl-D (`EndOfFileException`) — a real tty keeps accepting input
    * after that per-`readLine` exception, so unlike [[NumberedUi]]'s EOF this
    * always submits (trimmed, possibly blank; `Main.promptTask` re-prompts on
    * blank the same way it does for [[plainLineInput]]). Ctrl-C
    * (`UserInterruptException`) and a severed tty (`IOError`) cancel outright,
    * discarding whatever was typed so far.
    */
  def inputMultiline(prompt: String): UiOutcome[String] =
    val writer = terminal.writer()
    // Clear any stray bytes (e.g. a late coursier progress line) off the
    // current line before painting, like the other prompt entry points.
    print("[2K\r")
    writer.println()
    writer.println(
      AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
        .append("? ")
        .style(AttributedStyle.BOLD)
        .append(prompt)
        .append(":")
        .style(AttributedStyle.DEFAULT)
        .toAnsi(terminal)
    )
    writer.println(
      AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.faint())
        .append(ShellUi.multilineHint)
        .style(AttributedStyle.DEFAULT)
        .toAnsi(terminal)
    )
    writer.flush()
    val continuationPrompt = AttributedStringBuilder()
      .style(AttributedStyle.DEFAULT.faint())
      .append("… ")
      .style(AttributedStyle.DEFAULT)
      .toAnsi(terminal)

    // Not @tailrec: the recursive call sits inside a try/catch, which the JVM
    // can't turn into a loop — bounded by how many lines the user pastes, so
    // stack depth is never a real concern here.
    val lines = scala.collection.mutable.ArrayBuffer.empty[String]
    def loop(): UiOutcome[String] =
      try
        lines += lineReader.readLine(continuationPrompt)
        loop()
      catch
        case _: EndOfFileException =>
          UiOutcome.Selected(lines.mkString("\n").trim)
        case _: UserInterruptException | _: IOError => UiOutcome.Cancelled

    loop()

  /** Runs one prompt batch. ESC (an empty result map — ConsoleUI's own
    * cancel-to-empty-map behavior, enabled by `cancellableFirstPrompt`),
    * `UserInterruptException` (Ctrl-C), `EndOfFileException` and `IOError`
    * (both raised by jline's `BindingReader` on a severed tty) all surface as
    * [[UiOutcome.Cancelled]] (ADR 0021 §3).
    */
  private def runOrCancelled(
      consolePrompt: ConsolePrompt,
      elements: java.util.List[PromptableElementIF]
  ): UiOutcome[java.util.Map[String, PromptResultItemIF]] =
    try
      // A late byte from some other writer (coursier's fetch-progress
      // renderer on a fresh-cache run, research item 1) can still be sitting
      // on the current line right before this prompt paints — clear it first,
      // same treatment Main's banner print already gets.
      print("[2K\r")
      val results = consolePrompt.prompt(elements)
      if results.isEmpty then UiOutcome.Cancelled
      else UiOutcome.Selected(results)
    catch
      case _: UserInterruptException | _: EndOfFileException | _: IOError =>
        UiOutcome.Cancelled

private[ui] object ConsoleUiShell:

  /** `labels`, unchanged except duplicates get a `#<n>` suffix from their
    * second occurrence on — keeps every id unique so [[ConsoleUiShell.select]]
    * can map a `ListResult`'s selected id back to exactly one choice.
    */
  private[ui] def uniqueIds(labels: List[String]): List[String] =
    val seenCounts = scala.collection.mutable.Map.empty[String, Int]
    labels.map: label =>
      val occurrence = seenCounts.getOrElse(label, 0)
      seenCounts(label) = occurrence + 1
      if occurrence == 0 then label else s"$label#$occurrence"
