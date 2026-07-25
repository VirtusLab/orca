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
  ConsoleUiShell.registerKittyInterruptWidget(lineReader)
  ConsoleUiShell.registerKittyEofWidget(lineReader)

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
    * Pty-verified against jline `3.30.15` (a plain unit test can't drive real
    * terminal byte sequences):
    *
    *   - A paste (bracketed paste, on by default) lands intact in one go,
    *     embedded newlines included — never mistaken for a keypress.
    *   - Shift+Enter or Alt+Enter insert a literal newline instead of
    *     submitting ([[registerInsertNewlineWidget]]).
    *   - Ctrl-C cancels; Ctrl-D cancels only on an empty buffer (otherwise
    *     ordinary forward-delete, same as [[plainLineInput]] — so Ctrl-D can no
    *     longer "finish" a multi-line entry the way it used to; Enter does that
    *     now).
    *
    * The Shift+Enter/Ctrl-C/Ctrl-D bindings above only reach this read because
    * [[withKittyKeyboardProtocol]] wraps it — see that method's scaladoc for
    * the underlying kitty-protocol mechanism and why each needs its own widget.
    */
  def inputMultiline(prompt: String): UiOutcome[String] =
    withKittyKeyboardProtocol:
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

  /** THE canonical account of this file's kitty-keyboard-protocol mechanism —
    * every other method that touches it ([[inputMultiline]],
    * [[registerInsertNewlineWidget]], [[registerKittyInterruptWidget]],
    * [[registerKittyEofWidget]]) only states its own usage contract and points
    * back here.
    *
    * Proactively pushes the kitty keyboard protocol's "disambiguate escape
    * codes" flag (value `1`) for the duration of `body`, popped again in
    * `finally` regardless of outcome (submit, Ctrl-C, EOF, or any other
    * exception). This is what makes kitty-protocol-''capable'' terminals that
    * don't turn it on by default — including VS Code's integrated terminal —
    * start sending the distinct Shift+Enter byte sequence
    * [[registerInsertNewlineWidget]] binds, instead of the bare CR a plain
    * Enter also sends. Terminals that already send that sequence natively
    * (kitty, WezTerm, foot, Ghostty, recent xterm) simply see a flag they
    * already had pushed, pushed again — harmless, and popped back to the same
    * state. Terminals implementing the protocol not at all don't recognize
    * either escape sequence and ignore them outright, so pushing
    * unconditionally is safe.
    *
    * This flag is not as narrow as it sounds: per the kitty spec, it also
    * re-encodes ''every'' Ctrl+letter combination as a `CSI u` sequence rather
    * than its ordinary control byte — Enter, Tab and Backspace are the only
    * keys explicitly exempted (so a crashed program that left the flag pushed
    * doesn't also strand the user unable to type `reset`). That includes Ctrl-C
    * and Ctrl-D: a kitty-protocol terminal that honors this flag (again, VS
    * Code's integrated terminal among them) stops sending the raw bytes
    * `0x03`/`0x04` for as long as this flag is pushed, so neither the kernel's
    * `SIGINT` nor `LineReaderImpl`'s own `VEOF`-byte check ever fires —
    * [[inputMultiline]]'s read would otherwise never see either interrupt.
    * [[ConsoleUiShell.registerKittyInterruptWidget]] and
    * [[ConsoleUiShell.registerKittyEofWidget]] bind those re-encoded sequences
    * directly so both still work; see their scaladocs.
    *
    * `CSI > 1 u` ''pushes'' the flag onto the protocol's own flag stack; `CSI <
    * u` ''pops'' one level back off — so push-then-pop restores whatever the
    * terminal's own stack held before this call, exactly, even if some outer
    * code already pushed flags of its own.
    *
    * xterm's older `modifyOtherKeys` mode 2 was also considered as a wider-net
    * fallback for terminals that support it but not the kitty protocol, but
    * pty-verified (real terminal, not a unit test) to re-encode ''every''
    * Ctrl+letter combination as a `CSI 27 ; 5 ; <ascii-of-letter> ~` escape
    * sequence instead of its ordinary control byte — confirmed broken for
    * Ctrl-A (cursor-to-line-start), Ctrl-C (interrupt), and Ctrl-D
    * (EOF-or-delete): all three landed as literal garbage text in the buffer
    * instead of doing their normal jobs, since `LineReaderImpl` has no
    * keybinding for any of them (it relies on the terminal sending the
    * traditional byte). Compensating would mean rebinding every emacs Ctrl
    * combination the reader supports, not just the two or three this file
    * happens to document — too broad a regression surface for the benefit, so
    * `modifyOtherKeys` is deliberately never requested.
    *
    * Scoped to [[inputMultiline]]'s read only, not the whole shell: a
    * `select`/`confirm` prompt has no use for a literal-newline key, so pushing
    * this flag there would be pure risk (a leaked push on some other exit path)
    * for no benefit.
    */
  private[ui] def withKittyKeyboardProtocol[A](body: => A): A =
    val writer = terminal.writer()
    writer.write(ConsoleUiShell.KittyKeyboardProtocolPush)
    writer.flush()
    try body
    finally
      writer.write(ConsoleUiShell.KittyKeyboardProtocolPop)
      writer.flush()

private[ui] object ConsoleUiShell:

  // Requested by withKittyKeyboardProtocol around inputMultiline's read only;
  // see that method's scaladoc for why the push/pop pair is safe to emit
  // unconditionally and how it restores the terminal's prior flag stack.
  private[ui] val KittyKeyboardProtocolPush = "[>1u"
  private[ui] val KittyKeyboardProtocolPop = "[<u"

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
  private val kittyInterruptWidgetName = "orca-kitty-interrupt"
  private val kittyEofWidgetName = "orca-kitty-eof"

  /** Registers a widget on `reader` that writes a literal `\n` into the buffer
    * instead of submitting, bound to Alt+Enter's byte sequence (the portable
    * fallback, working without terminal support) and to Shift+Enter's two known
    * encodings (kitty protocol CSI-u, and xterm's `modifyOtherKeys` mode 2) —
    * used by [[ConsoleUiShell.inputMultiline]] so Enter alone means "submit"
    * while an explicit newline stays reachable mid-edit. Pty-verified: each
    * sequence inserts a newline without ending the read, and none disturb plain
    * Enter's own `accept-line` binding. See [[withKittyKeyboardProtocol]] for
    * why the kitty sequence reaches this widget at all. Called once per
    * `ConsoleUiShell` instance, from its constructor.
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
    mainKeyMap.bind(newline, "\r")
    mainKeyMap.bind(newline, "[13;2u")
    mainKeyMap.bind(newline, "[27;2;13~")

  /** Registers a widget on `reader` that throws [[UserInterruptException]] —
    * the same outcome a real Ctrl-C delivers via `SIGINT` — bound to the kitty
    * keyboard protocol's CSI-u encoding of Ctrl-C, so it reaches
    * [[inputMultiline]]'s catch clause exactly like the ordinary signal-driven
    * case. See [[withKittyKeyboardProtocol]] for why this escape sequence needs
    * its own binding at all. Called once per `ConsoleUiShell` instance, from
    * its constructor.
    */
  private[ui] def registerKittyInterruptWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      kittyInterruptWidgetName,
      () => throw UserInterruptException(reader.getBuffer.toString)
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    mainKeyMap.bind(Reference(kittyInterruptWidgetName), "[99;5u")

  /** Registers a widget on `reader` that reproduces `LineReaderImpl`'s own
    * Ctrl-D contract exactly, bound to the kitty keyboard protocol's CSI-u
    * encoding of Ctrl-D: buffer empty → throw [[EndOfFileException]] (same
    * outcome the raw byte gets via the `VEOF`-comparison check in
    * `LineReaderImpl`'s main loop); buffer non-empty → delegate to
    * `LineReader.DELETE_CHAR_OR_LIST`, the exact builtin widget the raw byte is
    * bound to in the default keymap, via `callWidget` — so mid-text this
    * behaves identically to plain Ctrl-D (deletes the character under the
    * cursor) without this file re-implementing that logic, cursor-at-end
    * included (pty-verified: with no completer configured here, that case is a
    * harmless no-op, same as plain Ctrl-D's). See [[withKittyKeyboardProtocol]]
    * for why this escape sequence needs its own binding at all, the same reason
    * as [[registerKittyInterruptWidget]]. Called once per `ConsoleUiShell`
    * instance, from its constructor.
    */
  private[ui] def registerKittyEofWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      kittyEofWidgetName,
      () =>
        if reader.getBuffer.length() == 0 then throw EndOfFileException()
        else
          reader.callWidget(LineReader.DELETE_CHAR_OR_LIST)
          true
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    mainKeyMap.bind(Reference(kittyEofWidgetName), "[100;5u")
