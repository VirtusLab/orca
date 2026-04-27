package orca.runner.terminal

import orca.{OrcaEvent, Usage}

import java.io.{ByteArrayOutputStream, PrintStream}

class TerminalInteractionTest extends munit.FunSuite:

  private def renderEvents(events: List[OrcaEvent]): String =
    val buf = new ByteArrayOutputStream()
    val interaction = new TerminalInteraction(
      out = new PrintStream(buf),
      useColor = false,
      animated = false
    )
    events.foreach(e => interaction.listeners.head.onEvent(e))
    buf.toString

  test("StageStarted prints a ▶ line; StageCompleted is silent in the log"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan", "done")
      )
    )
    assert(output.contains("plan"))
    assert(output.contains(TerminalInteraction.StageStartGlyph))
    assert(
      !output.contains(TerminalInteraction.StageDoneGlyph),
      s"StageCompleted must not render to the event log; got: $output"
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
      output.contains(s"${TerminalInteraction.StageStartGlyph} Switched"),
      s"Step should render with the ▶ glyph; got: $output"
    )
    assert(
      !output.contains(s"${TerminalInteraction.StageDoneGlyph} Switched"),
      s"Step events must never produce a closing ✔ line; got: $output"
    )

  test("errors are prefixed with an error marker"):
    val output = renderEvents(List(OrcaEvent.Error("boom")))
    assert(output.contains(TerminalInteraction.ErrorGlyph))
    assert(output.contains("boom"))

  test("TokensUsed events are ignored (owned by CostTracker)"):
    val output = renderEvents(
      List(OrcaEvent.TokensUsed("claude", Usage(10L, 5L, None)))
    )
    assertEquals(output, "")

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
    val outerStartLine = lines.find(l =>
      l.contains("outer") && l.contains(TerminalInteraction.StageStartGlyph)
    ).getOrElse(fail("outer start line missing"))
    val innerStartLine = lines.find(l =>
      l.contains("inner") && l.contains(TerminalInteraction.StageStartGlyph)
    ).getOrElse(fail("inner start line missing"))
    val errorLine = lines.find(_.contains("inside inner"))
      .getOrElse(fail("error line missing"))
    assert(!outerStartLine.startsWith(" "),
      s"outer marker should be flush left: '$outerStartLine'")
    assert(innerStartLine.startsWith("  ") && !innerStartLine.startsWith("    "),
      s"inner marker indented by 2: '$innerStartLine'")
    assert(errorLine.startsWith("    "),
      s"inner content indented by 4 (2 levels × 2 spaces): '$errorLine'")
    assert(
      !output.contains(TerminalInteraction.StageDoneGlyph),
      s"no ✔ should appear in the event log; got: $output"
    )
