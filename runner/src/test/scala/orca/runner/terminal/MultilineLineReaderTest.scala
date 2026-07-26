package orca.runner.terminal

import org.jline.reader.{
  EndOfFileException,
  LineReader,
  LineReaderBuilder,
  Reference,
  UserInterruptException
}
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.impl.DumbTerminal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets

class MultilineLineReaderTest extends munit.FunSuite:

  // The terminal actually distinguishing Shift+Enter because of this flag is
  // only pty-verifiable (a real terminal emulator has to interpret the CSI
  // sequence it's sent). What's a pure seam is the byte-level contract
  // withKittyKeyboardProtocol owns: the push is written and flushed before
  // body runs, and the pop follows once body returns — even if it throws —
  // restoring the terminal's flag stack on every exit path, per the rigor
  // requirement (submit/Ctrl-C/EOF/exception all reduce to "body completes or
  // throws" from this method's point of view). A DumbTerminal wrapping a
  // captured ByteArrayOutputStream is enough to observe exactly the bytes
  // written, without a real tty.
  test(
    "withKittyKeyboardProtocol writes the push sequence before body runs, and the pop sequence after"
  ):
    val out = ByteArrayOutputStream()
    val terminal = DumbTerminal(ByteArrayInputStream(Array.emptyByteArray), out)
    try
      var duringBody = ""
      MultilineLineReader.withKittyKeyboardProtocol(terminal):
        duringBody = out.toString(StandardCharsets.UTF_8)
      assertEquals(
        duringBody,
        MultilineLineReader.KittyKeyboardProtocolPush,
        "the push sequence must be written and flushed before body runs"
      )
      assertEquals(
        out.toString(StandardCharsets.UTF_8),
        MultilineLineReader.KittyKeyboardProtocolPush + MultilineLineReader.KittyKeyboardProtocolPop,
        "the pop sequence must follow once withKittyKeyboardProtocol returns"
      )
    finally terminal.close()

  test(
    "withKittyKeyboardProtocol writes the pop sequence even when body throws"
  ):
    val out = ByteArrayOutputStream()
    val terminal = DumbTerminal(ByteArrayInputStream(Array.emptyByteArray), out)
    try
      val _ = intercept[RuntimeException]:
        MultilineLineReader.withKittyKeyboardProtocol(terminal):
          throw new RuntimeException("prompt body blew up")
      assertEquals(
        out.toString(StandardCharsets.UTF_8),
        MultilineLineReader.KittyKeyboardProtocolPush + MultilineLineReader.KittyKeyboardProtocolPop
      )
    finally terminal.close()

  // Alt+Enter and the Shift+Enter CSI-u sequences are only pty-verifiable
  // end to end -- a real terminal has to actually send those byte sequences
  // for jline's BindingReader to dispatch them. What's pure here is the
  // wiring: each sequence resolves to the newline widget, the widget itself
  // inserts a literal newline, and — the thing an ESC-prefix typo would
  // silently break — bare Enter (no ESC) is left alone, still resolving to
  // jline's own `accept-line`, not to the newline widget.
  test(
    "registerInsertNewlineWidget binds Alt+Enter and both Shift+Enter sequences to a newline-inserting widget"
  ):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      MultilineLineReader.registerInsertNewlineWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)

      val altEnterBound = mainKeyMap.getBound("\u001b\r")
      val kittyShiftEnterBound = mainKeyMap.getBound("[13;2u")
      val xtermShiftEnterBound = mainKeyMap.getBound("[27;2;13~")
      List(altEnterBound, kittyShiftEnterBound, xtermShiftEnterBound).foreach:
        bound => assert(bound.isInstanceOf[Reference], s"must be bound: $bound")
      assertEquals(
        altEnterBound.asInstanceOf[Reference].name(),
        kittyShiftEnterBound.asInstanceOf[Reference].name(),
        "both sequences must resolve to the same widget"
      )
      assertEquals(
        altEnterBound.asInstanceOf[Reference].name(),
        xtermShiftEnterBound.asInstanceOf[Reference].name(),
        "both sequences must resolve to the same widget"
      )

      val widget =
        reader.getWidgets.get(altEnterBound.asInstanceOf[Reference].name())
      assert(widget.apply(), "the widget must report success")
      assertEquals(reader.getBuffer.toString, "\n")
    finally terminal.close()

  test("registerInsertNewlineWidget leaves plain Enter bound to accept-line"):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      MultilineLineReader.registerInsertNewlineWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
      val bareEnter = mainKeyMap.getBound("\r")
      assert(
        bareEnter.isInstanceOf[Reference]
          && bareEnter.asInstanceOf[Reference].name() == LineReader.ACCEPT_LINE,
        s"bare Enter must still submit, not insert a newline: $bareEnter"
      )
    finally terminal.close()

  // Whether a real terminal actually re-encodes Ctrl-C this way under the
  // pushed kitty flag is only pty-verifiable (see the manual pty repro in the
  // PR/report: a terminal that ignores the flag keeps sending the raw 0x03
  // byte, unaffected by this binding). What's pure here is the wiring: the
  // CSI-u sequence resolves to a widget, and that widget raises the same
  // exception a real Ctrl-C (SIGINT) does, so a multiline read's existing
  // catch clause handles both identically.
  test(
    "registerKittyInterruptWidget binds the kitty CSI-u Ctrl-C sequence to a widget that throws UserInterruptException"
  ):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      MultilineLineReader.registerKittyInterruptWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
      val bound = mainKeyMap.getBound("[99;5u")
      assert(bound.isInstanceOf[Reference], s"must be bound: $bound")

      val widget = reader.getWidgets.get(bound.asInstanceOf[Reference].name())
      intercept[UserInterruptException](widget.apply())
    finally terminal.close()

  // The empty-buffer branch (throw EndOfFileException) is exercisable as a
  // pure widget call. The non-empty branch delegates to
  // `LineReader.callWidget`, which requires an in-progress `readLine()` call
  // (it throws `IllegalStateException` otherwise) — so "mid-text deletes the
  // character under the cursor" and "cursor at end of non-empty text" are
  // pty-only, verified against a real read in the manual pty repro in the
  // PR/report, matching plain Ctrl-D's own behavior exactly (delegation, not
  // reimplementation, is what guarantees that match).
  test(
    "registerKittyEofWidget binds the kitty CSI-u Ctrl-D sequence to a widget that throws EndOfFileException on an empty buffer"
  ):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      MultilineLineReader.registerKittyEofWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
      val bound = mainKeyMap.getBound("[100;5u")
      assert(bound.isInstanceOf[Reference], s"must be bound: $bound")

      val widget = reader.getWidgets.get(bound.asInstanceOf[Reference].name())
      assertEquals(reader.getBuffer.length(), 0, "buffer starts empty")
      intercept[EndOfFileException](widget.apply())
    finally terminal.close()
