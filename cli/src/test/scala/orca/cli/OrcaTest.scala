package orca.cli

import _root_.orca.{FlowContext, OrcaEvent, orca, userPrompt}

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaTest extends munit.FunSuite:

  private def silentInteraction: TerminalInteraction =
    new TerminalInteraction(
      new PrintStream(new ByteArrayOutputStream()),
      useColor = false,
      animated = false
    )

  test(
    "orca runs the body and userPrompt accessor resolves against FlowContext"
  ):
    var seen: String = ""
    orca(args = OrcaArgs("hello world"), interaction = silentInteraction) {
      seen = userPrompt // top-level accessor resolves the ambient FlowContext
    }
    assertEquals(seen, "hello world")

  test("orca wires the interaction's listeners so emit reaches them"):
    val buf = new ByteArrayOutputStream()
    val interaction =
      new TerminalInteraction(
        new PrintStream(buf),
        useColor = false,
        animated = false
      )
    orca(interaction = interaction) {
      summon[FlowContext].emit(OrcaEvent.StageStarted("plan"))
    }
    assert(buf.toString.contains("plan"))
