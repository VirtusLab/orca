package orca.cli

import orca.*

import java.io.{ByteArrayOutputStream, PrintStream}

class TerminalInteractionTest extends munit.FunSuite:

  private def renderEvents(events: List[OrcaEvent]): String =
    val buf = new ByteArrayOutputStream()
    val interaction = new TerminalInteraction(
      out = new PrintStream(buf),
      useColor = false
    )
    events.foreach(e => interaction.listeners.head.onEvent(e))
    buf.toString

  test("stage and completion events render with stage names"):
    val output = renderEvents(
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan", "done")
      )
    )
    assert(output.contains("plan"))
    assert(output.contains(TerminalInteraction.StageStartGlyph))
    assert(output.contains(TerminalInteraction.StageDoneGlyph))

  test("LlmOutput is streamed verbatim without a newline appended"):
    val output = renderEvents(List(OrcaEvent.LlmOutput("hello ")))
    assertEquals(output, "hello ")

  test("errors are prefixed with an error marker"):
    val output = renderEvents(List(OrcaEvent.Error("boom")))
    assert(output.contains(TerminalInteraction.ErrorGlyph))
    assert(output.contains("boom"))

  test("TokensUsed events are ignored (owned by CostTracker)"):
    val output = renderEvents(
      List(OrcaEvent.TokensUsed(Usage(10L, 5L, None)))
    )
    assertEquals(output, "")

  test("runInteractive brackets the awaited handle with enter/exit banners"):
    val buf = new ByteArrayOutputStream()
    val interaction = new TerminalInteraction(
      out = new PrintStream(buf),
      useColor = false
    )
    val handle = new InteractiveHandle[Backend.ClaudeCode.type]:
      def awaitTermination(): LlmResult[Backend.ClaudeCode.type] =
        LlmResult(
          SessionId[Backend.ClaudeCode.type]("s"),
          "output",
          Usage.empty
        )
    interaction.runInteractive(handle)
    val output = buf.toString
    val enterIdx = output.indexOf("entering interactive session")
    val exitIdx = output.indexOf("interactive session ended")
    assert(enterIdx >= 0 && exitIdx > enterIdx, s"bad banner order: $output")

  test("runInteractive still prints the exit banner when the handle throws"):
    val buf = new ByteArrayOutputStream()
    val interaction = new TerminalInteraction(
      out = new PrintStream(buf),
      useColor = false
    )
    val handle = new InteractiveHandle[Backend.ClaudeCode.type]:
      def awaitTermination(): LlmResult[Backend.ClaudeCode.type] =
        throw new RuntimeException("session crashed")
    val _ = intercept[RuntimeException](interaction.runInteractive(handle))
    assert(buf.toString.contains("interactive session ended"))
