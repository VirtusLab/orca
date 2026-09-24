package orca.runner

import orca.{OrcaArgs, StackSettings, codingAgent, runFlow}
import orca.agents.Agent
import orca.testkit.GitRepo
import orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** Verifies that the coding-role agent resolved for a run (default claude, here
  * the wired stub) is reachable inside the body via the `codingAgent` accessor.
  */
class FlowContextAgentTest extends munit.FunSuite:

  test("the `codingAgent` accessor resolves the run's coding-role agent"):
    val workDir = GitRepo.seeded()
    var seen: Option[Agent[?]] = None
    val claude = StubAgent.claude
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        FlowHarness.request(
          args = OrcaArgs("test-agent"),
          stackSettings = Some(StackSettings.empty),
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = Nil,
          branchNaming = None,
          wiring = FlowWiring(claude = Some(_ => claude))
        )
      ):
        seen = Some(codingAgent)
    assert(
      seen.exists(_.sharesBackendWith(claude)),
      s"expected the `codingAgent` accessor to be the wired claude, got: $seen"
    )

end FlowContextAgentTest
