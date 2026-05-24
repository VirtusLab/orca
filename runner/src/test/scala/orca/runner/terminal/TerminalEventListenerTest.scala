package orca.runner.terminal

import orca.events.{OrcaEvent, Usage}
import orca.llm.Model
import java.io.{ByteArrayOutputStream, PrintStream}

/** These tests exercise the listener's synchronous state-mutation behaviour by
  * driving it directly with a synchronous `TerminalOutputState` — bypassing the
  * actor so the test reads output immediately rather than racing a worker
  * thread.
  */
class TerminalEventListenerTest extends munit.FunSuite:

  private def renderEvents(events: List[OrcaEvent]): String =
    renderWith(animated = false, events)

  private def renderWith(
      animated: Boolean,
      events: List[OrcaEvent]
  ): String =
    val buf = new ByteArrayOutputStream()
    val ps = new PrintStream(buf)
    val output =
      new TerminalOutputState(ps, useColor = false, animated = animated)
    val listener = new TerminalEventListener(output, useColor = false)
    events.foreach(listener.onEvent)
    buf.toString

  test("StageStarted prints a ▶ line; StageCompleted is silent in the log"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan", "done")
      )
    )
    assert(output.contains("plan"))
    assert(output.contains(TerminalEventListener.StageStartGlyph))
    assert(
      !output.contains(TerminalEventListener.StageDoneGlyph),
      s"StageCompleted must not render to the event log; got: $output"
    )

  test(
    "multi-line Step body re-indents continuation lines under the stage depth"
  ):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("review"),
        OrcaEvent.Step("[Warning] Issue summary\n  at src/Foo.scala:10"),
        OrcaEvent.StageCompleted("review", "")
      )
    )
    val lines = output.split('\n').toList
    assert(
      lines.exists(_.contains("Issue summary")),
      s"missing Step header line; got: $lines"
    )
    val locationLine = lines
      .find(_.contains("at src/Foo.scala:10"))
      .getOrElse(fail(s"missing location line; got: $lines"))
    // At depth 1 the indent is 2 spaces. The Step header has those 2
    // spaces plus the glyph; the location line also gets the 2-space
    // depth indent (so it sits at col 2 of the rendered line, plus
    // its own internal "  " hanging indent = col 4).
    assert(
      locationLine.startsWith("    "),
      s"continuation line should be re-indented under the stage; got: '$locationLine'"
    )

  test("Step events render as a single ▶ line, no closing ✔"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.Step("Switched to a new branch 'foo'"),
        OrcaEvent.StageCompleted("outer", "done")
      )
    )
    assert(output.contains("Switched to a new branch 'foo'"))
    assert(
      output.contains(s"${TerminalEventListener.StageStartGlyph} Switched"),
      s"Step should render with the ▶ glyph; got: $output"
    )
    assert(
      !output.contains(s"${TerminalEventListener.StageDoneGlyph} Switched"),
      s"Step events must never produce a closing ✔ line; got: $output"
    )

  test("errors are prefixed with an error marker"):
    val output = renderEvents(List(OrcaEvent.Error("boom")))
    assert(output.contains(TerminalEventListener.ErrorGlyph))
    assert(output.contains("boom"))

  test("TokensUsed events are ignored (owned by CostTracker)"):
    val output = renderEvents(
      List(
        OrcaEvent.TokensUsed(
          "claude",
          Some(Model("opus")),
          Usage(10L, 5L, None)
        )
      )
    )
    assertEquals(output, "")

  test(
    "status bar shows only the innermost stage (no breadcrumb concatenation)"
  ):
    val rendered = renderWith(
      animated = true,
      List(
        OrcaEvent.StageStarted(
          "Implement task: very long task title that would dominate"
        ),
        OrcaEvent.StageStarted("Implementation")
      )
    )
    // Find the most recent status redraw — the bytes after the last
    // ClearLine escape (`\r[2K`). Both names land in the event log via
    // the `▶` lines, but the status bar should only pin the innermost.
    val tail = rendered.split("\\[2K").last
    assert(
      tail.contains("Implementation"),
      s"status bar should pin the innermost stage; tail was: '$tail'"
    )
    assert(
      !tail.contains("very long task title"),
      s"outer stage title leaked into the status bar; tail was: '$tail'"
    )

  test("nested stages indent inner content; no ✔ ever appears in the log"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("outer"),
        OrcaEvent.StageStarted("inner"),
        OrcaEvent.Error("inside inner"),
        OrcaEvent.StageCompleted("inner", "done"),
        OrcaEvent.StageCompleted("outer", "done")
      )
    )
    val lines = output.split('\n').toList
    val outerStartLine = lines
      .find(l =>
        l.contains("outer") && l.contains(TerminalEventListener.StageStartGlyph)
      )
      .getOrElse(fail("outer start line missing"))
    val innerStartLine = lines
      .find(l =>
        l.contains("inner") && l.contains(TerminalEventListener.StageStartGlyph)
      )
      .getOrElse(fail("inner start line missing"))
    val errorLine = lines
      .find(_.contains("inside inner"))
      .getOrElse(fail("error line missing"))
    assert(
      !outerStartLine.startsWith(" "),
      s"outer marker should be flush left: '$outerStartLine'"
    )
    assert(
      innerStartLine.startsWith("  ") && !innerStartLine.startsWith("    "),
      s"inner marker indented by 2: '$innerStartLine'"
    )
    assert(
      errorLine.startsWith("    "),
      s"inner content indented by 4 (2 levels × 2 spaces): '$errorLine'"
    )
    assert(
      !output.contains(TerminalEventListener.StageDoneGlyph),
      s"no ✔ should appear in the event log; got: $output"
    )
