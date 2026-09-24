package orca.runner

import orca.testkit.{ScriptedBackend, TestAgent}
import orca.{FlowContext, OrcaArgs, StackSettings, flow}
import orca.agents.{BackendTag, OpencodeAgent}
import orca.plan.{Plan, Task, Title}
import orca.testkit.GitRepo
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}

/** End-to-end flow coverage for the OpenCode tool without a live server: the
  * backend-agnostic Plan DSL runs through a wired `OpencodeAgent`.
  */
class OpencodeFlowTest extends munit.FunSuite:

  // These tests drive gated LLM calls directly in the flow body (not inside a
  // `stage`), so mint the in-stage token for the suite (package `orca.runner`).
  private given orca.InStage = orca.InStage.unsafe

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body")),
    brief = "the brief"
  )

  test(
    "Plan.autonomous.from runs the real Plan DSL through the wired opencode tool"
  ):
    var observed: Option[Plan] = None
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      val canned: OpencodeAgent = TestAgent(
        ScriptedBackend.replying(BackendTag.Opencode)(_ =>
          ScriptedBackend.json(samplePlan)
        ),
        "canned"
      )
      flow(
        args = OrcaArgs(),
        stackSettings = Some(StackSettings.empty),
        codingAgent = Some(_.opencode),
        workDir = GitRepo.seeded(),
        opencode = Some(_ => canned),
        interaction = Some(interaction)
      ):
        observed = Some(
          Plan.autonomous
            .from("implement X", summon[FlowContext].opencode)
            .value
        )
    assertEquals(observed, Some(samplePlan))
