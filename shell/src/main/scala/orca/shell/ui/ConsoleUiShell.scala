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
  LineReader,
  LineReaderBuilder,
  UserInterruptException
}
import org.jline.terminal.Attributes.LocalFlag
import org.jline.terminal.{Attributes, Terminal}
import org.jline.utils.{AttributedStringBuilder, AttributedStyle}
import orca.runner.terminal.MultilineLineReader
import ox.discard

import java.io.IOError
import scala.annotation.tailrec

/** ConsoleUI-backed [[ShellUi]]: arrow-key prompts over
  * `org.jline.consoleui.prompt.ConsolePrompt` (ADR 0021 §3). Requires a real
  * tty — construct only via `ShellUi.make`, which gates on it; ConsoleUI NPEs
  * on non-tty stdin otherwise.
  *
  * ConsoleUI's single-choice list prompt has no non-selectable-item support,
  * and no way to apply per-item ANSI styling either (verified against
  * `jline-console-ui` 3.30.15). Disabled choices are therefore rendered with
  * their reason folded into the label (same text both backends use), and
  * picking one anyway prints [[Choice.disabledSelectionMessage]] via
  * [[ShellOutput.error]] before the prompt re-runs — an explained refusal, not
  * a silent one. The same list-item absence rules out honoring `preselect`'s
  * starting cursor position; it is a no-op here.
  *
  * A fresh `ConsolePrompt` is built for every top-level `select`/`confirm`/
  * `input` call rather than reused across the shell's lifetime:
  * `ConsolePrompt`'s `Display` tracks the cursor via its own render history
  * with no resync — sharing one across calls let any intervening output (even a
  * `println`) leave it stale, so the next prompt overwrote the previous answer
  * line instead of drawing below it (reproduced live). A fresh instance starts
  * with empty history.
  */
private[ui] final class ConsoleUiShell(terminal: Terminal) extends ShellUi:

  private val lineReader =
    LineReaderBuilder.builder().terminal(terminal).build()

  // Continuation lines within a single multi-line buffer (a paste, or a
  // literal newline from MultilineLineReader.registerInsertNewlineWidget) are
  // shown with the same "… " marker inputMultiline previously used per pasted
  // line.
  lineReader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "… ")
  MultilineLineReader.registerAll(lineReader)

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
    // ConsoleUI's post-answer summary line prints the item's id verbatim
    // (`ListResult.getDisplayResult` falls back to `getResult`, i.e. the
    // selected id), not its displayed text — an id of `index.toString` echoed
    // as e.g. "? Review agent 0" instead of the chosen label. Using the
    // rendered label itself as the id makes that echo human-readable; dedupe
    // defensively since ids must be unique (labels are unique in practice for
    // every menu/flow/harness list this renders today).
    val ids = ConsoleUiShell.uniqueIds(choices.map(_.renderedLabel))
    // A fresh ConsolePrompt per retry, not just per top-level call: picking a
    // disabled row prints its reason (below) before looping back here, and a
    // reused Display's stale bookkeeping (see the class doc) would then move
    // the cursor to redraw over that freshly printed line instead of below
    // it, clipping it.
    @tailrec def loop(): UiOutcome[A] =
      val consolePrompt = newConsolePrompt()
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
          if chosen.isEnabled then UiOutcome.Selected(chosen.value)
          else
            ShellOutput.error(chosen.disabledSelectionMessage)
            loop()

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
    print(ShellOutput.AnsiClearLine)
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

  /** Prints the prompt line, then reads the answer as a single `readLine` call
    * — Enter (`accept-line`) submits directly, like [[plainLineInput]]; no
    * "paste is fine" hint, since a multi-line answer needs no explaining.
    * Pty-verified against jline `3.30.15` (a plain unit test can't drive real
    * terminal byte sequences):
    *
    *   - A paste (bracketed paste, on by default) lands intact in one go,
    *     embedded newlines included — never mistaken for a keypress.
    *   - Shift+Enter or Alt+Enter insert a literal newline instead of
    *     submitting ([[MultilineLineReader.registerInsertNewlineWidget]]).
    *   - Ctrl-C cancels; Ctrl-D cancels only on an empty buffer, otherwise
    *     ordinary forward-delete (same as [[plainLineInput]]).
    *
    * The Shift+Enter/Ctrl-C/Ctrl-D bindings above only reach this read because
    * [[MultilineLineReader.withKittyKeyboardProtocol]] wraps it — see that
    * method's scaladoc for the underlying kitty-protocol mechanism and why each
    * needs its own widget.
    */
  def inputMultiline(prompt: String): UiOutcome[String] =
    MultilineLineReader.withKittyKeyboardProtocol(terminal):
      val writer = terminal.writer()
      // Clear any stray bytes (e.g. a late coursier progress line) off the
      // current line before painting, like the other prompt entry points.
      print(ShellOutput.AnsiClearLine)
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
      writer.flush()
      val continuationPrompt = AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.faint())
        .append("… ")
        .style(AttributedStyle.DEFAULT)
        .toAnsi(terminal)

      try UiOutcome.Selected(lineReader.readLine(continuationPrompt).trim)
      catch
        case _: UserInterruptException | _: EndOfFileException | _: IOError =>
          UiOutcome.Cancelled

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
      // renderer on a fresh-cache run) can still be sitting on the current line
      // right before this prompt paints — clear it first, same treatment
      // Main's banner print already gets.
      print(ShellOutput.AnsiClearLine)
      val results = withIsigDisabled(consolePrompt.prompt(elements))
      if results.isEmpty then UiOutcome.Cancelled
      else UiOutcome.Selected(results)
    catch
      case _: UserInterruptException | _: EndOfFileException | _: IOError =>
        UiOutcome.Cancelled

  /** Disables the terminal's `ISIG` local flag for the duration of `body`
    * (restored in `finally`, regardless of outcome).
    *
    * `ConsolePrompt.open()` calls jline's `Terminal.enterRawMode()`, which
    * turns off `ICANON`/`ECHO`/`IEXTEN` but deliberately leaves `ISIG`
    * untouched (verified against the jline 3.30.15 `AbstractTerminal` source).
    * With `ISIG` on, the kernel's tty driver intercepts Ctrl-C itself and
    * raises a real `SIGINT` before the byte ever reaches jline — and since this
    * shell's `Terminal` installs no signal handler ([[ShellUi.buildTerminal]]),
    * that SIGINT falls through to the JVM's default handling, killing the
    * process (the kill this fixes).
    *
    * The plain-`LineReader` paths ([[plainLineInput]], [[inputMultiline]])
    * don't need this: `LineReaderImpl.readLine()` installs its own SIGINT
    * handler for the duration of the read (jar-verified), converting a
    * delivered signal into an interrupt of the reading thread regardless of
    * `ISIG`. `ConsolePrompt`'s prompts read via a raw `BindingReader` with no
    * such hook, so they need Ctrl-C to arrive as an ordinary byte instead.
    */
  private[ui] def withIsigDisabled[A](body: => A): A =
    val original = terminal.getAttributes
    val relaxed = Attributes(original)
    relaxed.setLocalFlag(LocalFlag.ISIG, false)
    terminal.setAttributes(relaxed)
    try body
    finally terminal.setAttributes(original)

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
