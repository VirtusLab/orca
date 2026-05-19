package orca.runner

import orca.{FlowContext, OrcaArgs, flow, userPrompt}
import orca.events.{OrcaEvent}
import _root_.orca.runner.terminal.TerminalInteraction

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaTest extends munit.FunSuite:

  private def silentInteraction: TerminalInteraction =
    new TerminalInteraction(
      new PrintStream(new ByteArrayOutputStream()),
      useColor = false,
      animated = false
    )

  test(
    "flow runs the body and userPrompt accessor resolves against FlowContext"
  ):
    var seen: String = ""
    flow(
      args = OrcaArgs("hello world"),
      interaction = Some(silentInteraction)
    ) {
      seen = userPrompt // top-level accessor resolves the ambient FlowContext
    }
    assertEquals(seen, "hello world")

  test("flow wires the interaction's listeners so emit reaches them"):
    val buf = new ByteArrayOutputStream()
    val interaction =
      new TerminalInteraction(
        new PrintStream(buf),
        useColor = false,
        animated = false
      )
    flow(args = OrcaArgs(), interaction = Some(interaction)) {
      summon[FlowContext].emit(OrcaEvent.StageStarted("plan"))
    }
    assert(buf.toString.contains("plan"))
