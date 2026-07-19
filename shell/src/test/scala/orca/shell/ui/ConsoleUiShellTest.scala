package orca.shell.ui

import org.jline.reader.{LineReader, LineReaderBuilder, Reference}
import org.jline.terminal.Attributes
import org.jline.terminal.TerminalBuilder

class ConsoleUiShellTest extends munit.FunSuite:

  // The Ctrl-C-cancels-instead-of-killing-the-shell fix itself is only
  // pty-verifiable (jline's SIGINT/ISIG plumbing needs a real tty to exercise
  // end to end — see the manual pty repro in the PR/report), but
  // `withIsigDisabled`'s own save/mutate/restore contract doesn't: a `dumb`
  // terminal (no real tty) is enough to check ISIG is off during `body` and
  // restored after, mirroring `ChildTerminalTest`'s attribute-restore test.
  test("withIsigDisabled turns ISIG off during body and restores it after"):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val shell = ConsoleUiShell(terminal)
      val before =
        terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG)
      var duringBody = true
      shell.withIsigDisabled:
        duringBody =
          terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG)
      assertEquals(duringBody, false, "ISIG must be off while body runs")
      assertEquals(
        terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG),
        before,
        "ISIG must be restored to its prior value once withIsigDisabled returns"
      )
    finally terminal.close()

  test("withIsigDisabled restores ISIG even when body throws"):
    val terminal = TerminalBuilder.builder().dumb(true).build()
    try
      val shell = ConsoleUiShell(terminal)
      val before =
        terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG)
      val _ = intercept[RuntimeException]:
        shell.withIsigDisabled:
          throw new RuntimeException("prompt body blew up")
      assertEquals(
        terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG),
        before
      )
    finally terminal.close()

  test("uniqueIds leaves already-unique labels untouched"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("claude", "codex", "gemini")),
      List("claude", "codex", "gemini")
    )

  test("uniqueIds suffixes every repeat of a duplicate label with a counter"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("claude", "claude", "codex", "claude")),
      List("claude", "claude#1", "codex", "claude#2")
    )

  test("uniqueIds keeps distinct duplicate groups independent"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("a", "b", "a", "b", "b")),
      List("a", "b", "a#1", "b#1", "b#2")
    )

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
      ConsoleUiShell.registerInsertNewlineWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)

      val altEnterBound = mainKeyMap.getBound("\r")
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
      ConsoleUiShell.registerInsertNewlineWidget(reader)
      val mainKeyMap = reader.getKeyMaps.get(LineReader.MAIN)
      val bareEnter = mainKeyMap.getBound("\r")
      assert(
        bareEnter.isInstanceOf[Reference]
          && bareEnter.asInstanceOf[Reference].name() == LineReader.ACCEPT_LINE,
        s"bare Enter must still submit, not insert a newline: $bareEnter"
      )
    finally terminal.close()
