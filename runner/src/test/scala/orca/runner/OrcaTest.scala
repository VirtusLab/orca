package orca.runner

import orca.{FlowContext, OrcaArgs, StackSettings, flow, userPrompt}
import orca.events.{OrcaEvent}
import orca.testkit.GitRepo
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaTest extends munit.FunSuite:

  test(
    "flow runs the body and userPrompt accessor resolves against FlowContext"
  ):
    var seen: String = ""
    // Outer supervised scope owns the test-supplied TerminalInteraction.
    // `flow` opens its own supervised inside, but the interaction's worker
    // lives in this one — graceful drain on scope exit.
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs("hello world"),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = GitRepo.seeded(),
        interaction = Some(interaction)
      ):
        seen = userPrompt
    assertEquals(seen, "hello world")

  test("flow wires the interaction's listeners so emit reaches them"):
    val buf = new ByteArrayOutputStream()
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(buf),
        useColor = false,
        animated = false
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        agent = _ => StubAgent.claude,
        workDir = GitRepo.seeded(),
        interaction = Some(interaction)
      ):
        summon[FlowContext].emit(OrcaEvent.StageStarted("plan"))
    // By the time the outer supervised exits, the interaction's worker has
    // drained — `flow`'s finally closes the channel, and the supervised
    // scope joins the forkUser worker before returning.
    assert(buf.toString.contains("plan"))
