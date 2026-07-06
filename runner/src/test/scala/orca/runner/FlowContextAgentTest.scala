package orca.runner

import orca.{OrcaArgs, agent, runFlow}
import orca.agents.Agent
import orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** Verifies that the `agent` selector passed to `runFlow` is resolved against
  * the flow context and reachable inside the body via the `agent` accessor (the
  * lead is no longer exposed as a `FlowContext` member).
  */
class FlowContextAgentTest extends munit.FunSuite:

  test("the `agent` accessor resolves the selector passed to runFlow"):
    val workDir = TempRepo.create()
    var seen: Option[Agent[?]] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        args = OrcaArgs("test-agent"),
        agent = _ => StubAgent.claude,
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = Nil,
        branchNaming = None,
        returnToStartBranch = false,
        progressStore = None
      ):
        seen = Some(agent)
    assert(
      seen.exists(_ eq StubAgent.claude),
      s"expected the `agent` accessor to be StubAgent.claude but got: $seen"
    )

end FlowContextAgentTest
