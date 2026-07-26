package orca.runner.terminal

import org.jline.reader.{
  EndOfFileException,
  LineReader,
  Reference,
  UserInterruptException
}
import org.jline.terminal.Terminal

/** Multiline text entry for a JLine [[LineReader]]: a literal-newline key
  * (Alt+Enter, or Shift+Enter where the terminal distinguishes it) so Enter
  * alone submits, plus the kitty keyboard protocol plumbing that makes
  * Shift+Enter/Ctrl-C/Ctrl-D distinguishable on terminals that support it.
  * [[registerAll]] wires the widgets onto a reader once at construction;
  * [[withKittyKeyboardProtocol]] wraps the `readLine` call itself. Shared by
  * `orca.shell.ui.ConsoleUiShell.inputMultiline` (the shell's task/goal/fork
  * prompt) and [[ConversationRenderer.JLinePrompter]] (ask-user prompts during
  * a flow) — pty-verified against jline `3.30.15` for both call sites (a plain
  * unit test can't drive real terminal byte sequences):
  *
  *   - A paste (bracketed paste, on by default) lands intact in one go,
  *     embedded newlines included — never mistaken for a keypress.
  *   - Shift+Enter or Alt+Enter insert a literal newline instead of submitting
  *     ([[registerInsertNewlineWidget]]).
  *   - Ctrl-C cancels; Ctrl-D cancels only on an empty buffer (otherwise
  *     ordinary forward-delete).
  *
  * [[withKittyKeyboardProtocol]]'s scaladoc is THE canonical account of the
  * underlying kitty-protocol mechanism — every other method here only states
  * its own usage contract and points back to it.
  */
private[orca] object MultilineLineReader:

  // Requested by withKittyKeyboardProtocol around a multiline read only; see
  // that method's scaladoc for why the push/pop pair is safe to emit
  // unconditionally and how it restores the terminal's prior flag stack.
  private[orca] val KittyKeyboardProtocolPush = "\u001b[>1u"
  private[orca] val KittyKeyboardProtocolPop = "\u001b[<u"

  private val insertNewlineWidgetName = "orca-insert-newline"
  private val kittyInterruptWidgetName = "orca-kitty-interrupt"
  private val kittyEofWidgetName = "orca-kitty-eof"

  /** Registers every widget below on `reader`'s main keymap —
    * [[registerInsertNewlineWidget]], [[registerKittyInterruptWidget]],
    * [[registerKittyEofWidget]] — in one call. Call once per `LineReader`, at
    * construction.
    */
  private[orca] def registerAll(reader: LineReader): Unit =
    registerInsertNewlineWidget(reader)
    registerKittyInterruptWidget(reader)
    registerKittyEofWidget(reader)

  /** Registers a widget on `reader` that writes a literal `\n` into the buffer
    * instead of submitting, bound to Alt+Enter's byte sequence (the portable
    * fallback, working without terminal support) and to Shift+Enter's two known
    * encodings (kitty protocol CSI-u, and xterm's `modifyOtherKeys` mode 2) —
    * so Enter alone means "submit" while an explicit newline stays reachable
    * mid-edit. Pty-verified: each sequence inserts a newline without ending the
    * read, and none disturb plain Enter's own `accept-line` binding. See
    * [[withKittyKeyboardProtocol]] for why the kitty sequence reaches this
    * widget at all.
    */
  private[orca] def registerInsertNewlineWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      insertNewlineWidgetName,
      () =>
        reader.getBuffer.write('\n')
        true
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    val newline = Reference(insertNewlineWidgetName)
    mainKeyMap.bind(newline, "\u001b\r")
    mainKeyMap.bind(newline, "\u001b[13;2u")
    mainKeyMap.bind(newline, "\u001b[27;2;13~")

  /** Registers a widget on `reader` that throws [[UserInterruptException]] —
    * the same outcome a real Ctrl-C delivers via `SIGINT` — bound to the kitty
    * keyboard protocol's CSI-u encoding of Ctrl-C, so it reaches a multiline
    * read's catch clause exactly like the ordinary signal-driven case. See
    * [[withKittyKeyboardProtocol]] for why this escape sequence needs its own
    * binding at all.
    */
  private[orca] def registerKittyInterruptWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      kittyInterruptWidgetName,
      () => throw UserInterruptException(reader.getBuffer.toString)
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    mainKeyMap.bind(Reference(kittyInterruptWidgetName), "\u001b[99;5u")

  /** Registers a widget on `reader` that reproduces `LineReaderImpl`'s own
    * Ctrl-D contract exactly, bound to the kitty keyboard protocol's CSI-u
    * encoding of Ctrl-D: buffer empty → throw [[EndOfFileException]] (same
    * outcome the raw byte gets via the `VEOF`-comparison check in
    * `LineReaderImpl`'s main loop); buffer non-empty → delegate to
    * `LineReader.DELETE_CHAR_OR_LIST`, the exact builtin widget the raw byte is
    * bound to in the default keymap, via `callWidget` — so mid-text this
    * behaves identically to plain Ctrl-D (deletes the character under the
    * cursor) without reimplementing that logic, cursor-at-end included
    * (pty-verified: with no completer configured, that case is a harmless
    * no-op, same as plain Ctrl-D's). See [[withKittyKeyboardProtocol]] for why
    * this escape sequence needs its own binding at all, the same reason as
    * [[registerKittyInterruptWidget]].
    */
  private[orca] def registerKittyEofWidget(reader: LineReader): Unit =
    reader.getWidgets.put(
      kittyEofWidgetName,
      () =>
        if reader.getBuffer.length() == 0 then throw EndOfFileException()
        else
          reader.callWidget(LineReader.DELETE_CHAR_OR_LIST)
          true
    )
    val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
    mainKeyMap.bind(Reference(kittyEofWidgetName), "\u001b[100;5u")

  /** Proactively pushes the kitty keyboard protocol's "disambiguate escape
    * codes" flag (value `1`) on `terminal` for the duration of `body`, popped
    * again in `finally` regardless of outcome (submit, Ctrl-C, EOF, or any
    * other exception). This is what makes kitty-protocol-''capable'' terminals
    * that don't turn it on by default — including VS Code's integrated terminal
    * — start sending the distinct Shift+Enter byte sequence
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
    * `SIGINT` nor `LineReaderImpl`'s own `VEOF`-byte check ever fires — a
    * multiline read would otherwise never see either interrupt.
    * [[registerKittyInterruptWidget]] and [[registerKittyEofWidget]] bind those
    * re-encoded sequences directly so both still work; see their scaladocs.
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
    * combination the reader supports, not just the two or three documented here
    * — too broad a regression surface for the benefit, so `modifyOtherKeys` is
    * deliberately never requested.
    *
    * Scoped to a single multiline read, not the caller's whole lifetime: a
    * `select`/`confirm` prompt (ConsoleUI) has no use for a literal-newline
    * key, so pushing this flag there would be pure risk (a leaked push on some
    * other exit path) for no benefit.
    */
  private[orca] def withKittyKeyboardProtocol[A](terminal: Terminal)(
      body: => A
  ): A =
    val writer = terminal.writer()
    writer.write(KittyKeyboardProtocolPush)
    writer.flush()
    try body
    finally
      writer.write(KittyKeyboardProtocolPop)
      writer.flush()
