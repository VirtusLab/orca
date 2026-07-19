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
  Reference,
  UserInterruptException
}
import org.jline.terminal.Attributes.LocalFlag
import org.jline.terminal.{Attributes, Terminal}
import org.jline.utils.{AttributedStringBuilder, AttributedStyle}
import ox.discard

import java.io.IOError
import scala.annotation.tailrec

/** ConsoleUI-backed [[ShellUi]]: arrow-key prompts over
  * `org.jline.consoleui.prompt.ConsolePrompt` (ADR 0021 §3). Requires a real
  * tty — construct only via `ShellUi.make`, which gates on it; ConsoleUI NPEs
  * on non-tty stdin otherwise.
  *
  * ConsoleUI's single-choice list prompt has no non-selectable-item support in
  * its public API — `ListItem.isSelectable()` is hardcoded `true`, and
  * `ListPromptBuilder` exposes no way to add a `Separator`; only the checkbox
  * prompt's items carry a disabled flag (jar-verified against
  * `jline-console-ui` 3.30.15). Disabled choices are therefore rendered with
  * their reason folded into the label and, if picked anyway, the prompt simply
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

  // Continuation lines within a single multi-line buffer (a paste, or a
  // literal newline from ConsoleUiShell.insertNewlineWidgetName) are shown
  // with the same "… " marker inputMultiline previously used per pasted line.
  lineReader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "… ")
  ConsoleUiShell.registerInsertNewlineWidget(lineReader)

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
    * Pasting and Shift/Alt+Enter are pty-verified against jline `3.30.15` (a
    * plain unit test can't drive real terminal byte sequences):
    *
    *   - '''Bracketed paste'''. `Option.BRACKETED_PASTE` defaults to `true` and
    *     `BEGIN_PASTE` is bound to the terminal's paste-start sequence in the
    *     default keymap (`LineReaderImpl.bindArrowKeys`), so a paste arrives as
    *     one `beginPaste()` call that writes the whole captured block —
    *     embedded newlines included — into the buffer as data. A pasted newline
    *     is therefore never mistaken for a keypress; the terminal must actually
    *     support bracketed paste for this to fire (most modern terminal
    *     emulators and `tmux` do).
    *   - '''Shift+Enter / Alt+Enter insert a newline'''.
    *     [[ConsoleUiShell.registerInsertNewlineWidget]] binds a custom widget
    *     for both — see its own scaladoc for exactly which byte sequences.
    *
    * Ctrl-D on an empty buffer and Ctrl-C both cancel, exactly like
    * [[plainLineInput]] (`LineReaderImpl`'s own main loop throws
    * `EndOfFileException` for Ctrl-D only when the buffer is empty — on a
    * non-empty buffer it's ordinary editing, deleting the character under the
    * cursor — so Ctrl-D can no longer "finish" a multi-line entry the way it
    * used to; Enter does that now).
    */
  def inputMultiline(prompt: String): UiOutcome[String] =
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
    * `ConsolePrompt.open()` only calls jline's `Terminal.enterRawMode()`, which
    * turns off `ICANON`/`ECHO`/`IEXTEN` but deliberately leaves `ISIG`
    * untouched (verified against the jline 3.30.15 `AbstractTerminal` source).
    * With `ISIG` on, the kernel's tty driver intercepts Ctrl-C itself and
    * raises a real `SIGINT` — the byte `0x03` never reaches jline's
    * `BindingReader`, so `AbstractPrompt`'s own `ctrl('C')` keymap binding
    * (which throws `UserInterruptException` — jar-verified) never fires. Since
    * this shell's `Terminal` is built via a plain
    * `TerminalBuilder.builder()...build()` ([[ShellUi.buildTerminal]]) with no
    * `signalHandler` override, its registered `SIGINT` disposition is jline's
    * own default, `SIG_DFL` — so the signal falls through to the JVM's default
    * handling (process termination), which is the RC-130 kill this fixes.
    *
    * The plain-`LineReader` paths ([[plainLineInput]], [[inputMultiline]])
    * don't need this: `LineReaderImpl.readLine()` installs its own
    * `terminal.handle(Signal.INT, ...)` for the duration of the read (also
    * jar-verified), converting a delivered `SIGINT` into an interrupt of the
    * reading thread regardless of `ISIG`. `ConsolePrompt`'s prompts read via a
    * raw `BindingReader` instead and install no such signal hook, so they need
    * Ctrl-C to arrive as an ordinary byte instead — hence turning `ISIG` off
    * here rather than installing a signal handler.
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

  private val insertNewlineWidgetName = "orca-insert-newline"

  /** Registers a widget on `reader` that writes a literal `\n` into the buffer
    * instead of submitting, and binds it to Alt+Enter's and Shift+Enter's byte
    * sequences — used by [[ConsoleUiShell.inputMultiline]] so Enter alone means
    * "submit" while an explicit newline stays reachable mid-edit. Pty-verified:
    * each sequence bound below inserts a newline without ending the read, and
    * doesn't disturb plain Enter's own `accept-line` binding (bare `CR`/`LF`,
    * deliberately not touched here). Called once per `ConsoleUiShell` instance,
    * from its constructor.
    */
  private[ui] def registerInsertNewlineWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      insertNewlineWidgetName,
      () =>
        reader.getBuffer.write('\n')
        true
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    val newline = Reference(insertNewlineWidgetName)
    // Alt+Enter: a raw-mode terminal's Enter key always sends CR (0x0D);
    // "Meta sends Escape" (the near-universal default for Alt) prefixes it
    // with ESC. This is the portable fallback — it works in nearly every
    // terminal, the same convention Claude Code's own CLI relies on.
    mainKeyMap.bind(newline, "\r")
    // Shift+Enter: the kitty keyboard protocol's CSI-u encoding (also xterm's
    // `modifyOtherKeys` mode 2) — kitty, WezTerm, foot, Ghostty, recent
    // xterm/VS Code all send this. The second binding is xterm's older
    // `modifyOtherKeys` mode 1 encoding of the same key. Terminals
    // implementing neither (GNOME Terminal/VTE, macOS Terminal.app, xterm
    // with modifyOtherKeys off) send a plain CR for Shift+Enter —
    // indistinguishable from Enter itself — which is exactly why Alt+Enter
    // above exists as a fallback that doesn't depend on terminal support.
    mainKeyMap.bind(newline, "[13;2u")
    mainKeyMap.bind(newline, "[27;2;13~")
