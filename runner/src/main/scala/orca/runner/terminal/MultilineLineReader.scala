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
    * Ctrl-D contract, bound to the kitty protocol's CSI-u encoding of Ctrl-D:
    * empty buffer → throw [[EndOfFileException]] (same as the raw `VEOF` byte);
    * non-empty → delegate to `LineReader.DELETE_CHAR_OR_LIST` via `callWidget`,
    * the exact builtin the raw byte is bound to — so mid-text this matches
    * plain Ctrl-D exactly, without reimplementing it (pty-verified:
    * cursor-at-end is a harmless no-op with no completer configured). See
    * [[withKittyKeyboardProtocol]] for why this sequence needs its own binding.
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

  /** Pushes the kitty keyboard protocol's "disambiguate escape codes" flag
    * (`CSI > 1 u`) on `terminal` for `body`'s duration, popping it (`CSI < u`)
    * in `finally` regardless of outcome. Push/pop is a stack operation, so this
    * restores whatever flag state the terminal held before the call, even under
    * nested pushes. Safe to call unconditionally: terminals that already send
    * the kitty sequences natively see a redundant push/pop; terminals that
    * don't implement the protocol at all ignore both sequences.
    *
    * This is what makes kitty-capable-but-inert terminals (including VS Code's
    * integrated terminal) start sending [[registerInsertNewlineWidget]]'s
    * Shift+Enter sequence instead of a bare CR.
    *
    * The flag is broader than the name suggests: it re-encodes EVERY
    * Ctrl+letter combination as `CSI u` (Enter/Tab/Backspace exempted, so a
    * crashed program with the flag stuck doesn't strand the user unable to type
    * `reset`) — including Ctrl-C/Ctrl-D, which stop delivering `SIGINT`/the raw
    * `VEOF` byte while the flag is pushed. [[registerKittyInterruptWidget]] and
    * [[registerKittyEofWidget]] bind the re-encoded sequences directly so both
    * still work.
    *
    * xterm's older `modifyOtherKeys` mode 2 was also tried as a wider-net
    * fallback, but pty-verified to mangle Ctrl-A/C/D into literal garbage text
    * (no keybinding exists for the resulting escape sequences) — rebinding
    * every affected combination was too broad a regression surface for the
    * benefit, so it's deliberately never requested.
    *
    * Scoped to one multiline read, not the caller's whole lifetime: a
    * `select`/`confirm` prompt has no use for a literal-newline key, so pushing
    * this flag there would be pure risk for no benefit.
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
