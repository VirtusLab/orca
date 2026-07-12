package orca.plan

import orca.events.EventDispatcher
import orca.agents.{BackendTag, SessionId}

/** Runtime wiring of the autonomous planning grid: each operation pairs its
  * result with the producing session, and `triage` converts the wire
  * [[BugTriage]] into a [[Triage]]. The conversions themselves are covered by
  * [[AssessThenPlanTest]] (toVerdict) and [[BugTriageTest]] (toTriage); the
  * interactive cells share the same helper and are pinned at compile time by
  * `flowtests.FlowCompilesTest`.
  */
class PlanGridTest extends munit.FunSuite:

  private given orca.FlowContext =
    new orca.TestFlowContext(new EventDispatcher(Nil))

  // Planning helpers are now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body")),
    brief = "the brief"
  )

  test("autonomous.from pairs the plan with the producing conversation"):
    val agent = new CannedResultAgent(samplePlan)
    val result = Plan.autonomous.from("prompt", agent)
    assertEquals(
      Some(result.chat.id),
      agent.lastSession,
      "the returned chat must continue the planning turn's conversation"
    )
    assertEquals(result.value, samplePlan)

  test("autonomous.triage converts the wire BugTriage into a Triage"):
    val wire = BugTriage(
      isBug = true,
      notBugExplanation = "",
      canTest = true,
      reproductionSteps = "",
      failingTestPath = Some("src/test/scala/FooTest.scala"),
      branchName = "fix-foo",
      summary = "Foo overflows"
    )
    val agent = new CannedResultAgent(wire)
    val result = Plan.autonomous.triage("report", agent)
    assertEquals(Some(result.chat.id), agent.lastSession)
    assertEquals(
      result.value,
      Triage.Testable(
        "Foo overflows",
        "fix-foo",
        "src/test/scala/FooTest.scala"
      )
    )

  test(
    "the handed-out chat is bound to the base agent, not the NetworkOnly sibling"
  ):
    // CannedResultAgent.withTools returns `this`, which would mask a
    // regression to handing out the restricted planning chat — so the base
    // here routes withTools to a DISTINCT restricted sibling.
    val restricted = new CannedResultAgent(samplePlan)
    val base = new CannedResultAgent(samplePlan):
      override def withTools(
          tools: orca.agents.ToolSet
      ): orca.agents.Agent[BackendTag.ClaudeCode.type] = restricted
    val result = Plan.autonomous.from("prompt", base)
    assert(
      result.chat.agent eq base,
      "a continuation must regain the base agent's capability"
    )
    assert(
      restricted.lastSession.isDefined,
      "the planning turn must run on the restricted sibling"
    )

  // --- post-planning step (reviewed) on the planning session ---

  private def sessioned[A](value: A): Sessioned[BackendTag.ClaudeCode.type, A] =
    Sessioned(
      new CannedResultAgent(value)
        .chat(SessionId[BackendTag.ClaudeCode.type]("planner-sid")),
      value
    )

  test("reviewed returns the improved plan on the original chat binding"):
    val improved = samplePlan.copy(description = "tighter", brief = "sharper")
    val input = sessioned(samplePlan)
    val result = input.reviewed(new CannedResultAgent(improved))
    assertEquals(result.value, improved)
    assert(
      result.chat eq input.chat,
      "reviewed must hand back the original chat, not the review sibling's"
    )
