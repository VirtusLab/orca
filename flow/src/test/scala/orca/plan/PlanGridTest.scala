package orca.plan

import orca.events.EventDispatcher

/** Runtime wiring of the autonomous planning grid: each operation pairs its
  * result with the producing chat, and `triage` converts the wire [[BugTriage]]
  * into a [[Triage]]. The conversions themselves are covered by
  * [[AssessThenPlanTest]] (toVerdict) and [[BugTriageTest]] (toTriage); the
  * interactive cells share the same helper and are pinned at compile time by
  * `flowtests.FlowCompilesTest`.
  */
class PlanGridTest extends munit.FunSuite:

  private given orca.FlowContext =
    new orca.TestFlowContext(new EventDispatcher(Nil))

  // Planning helpers are gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body")),
    brief = "the brief"
  )

  test("autonomous.from pairs the plan with the producing conversation"):
    val canned = new CannedResult(samplePlan)
    val result = Plan.autonomous.from("prompt", canned.agent)
    assertEquals(
      Some(result.chat.id.value),
      canned.lastSession,
      "the returned chat must continue the planning turn's conversation"
    )
    assertEquals(result.value, samplePlan)

  test("autonomous.triage converts the wire BugTriage into a Triage"):
    val wire = BugTriage(
      kind = BugTriage.Kind.Testable,
      notBugExplanation = "",
      reproductionSteps = "",
      failingTestPath = Some("src/test/scala/FooTest.scala"),
      branchName = "fix-foo",
      summary = "Foo overflows"
    )
    val canned = new CannedResult(wire)
    val result = Plan.autonomous.triage("report", canned.agent)
    assertEquals(Some(result.chat.id.value), canned.lastSession)
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
    val canned = new CannedResult(samplePlan)
    val result = Plan.autonomous.from("prompt", canned.agent)
    assert(
      result.chat.agent eq canned.agent,
      "a continuation must regain the base agent's capability"
    )
    assertEquals(
      canned.lastToolSet,
      Some(orca.agents.ToolSet.NetworkOnly),
      "the planning turn must run on the restricted sibling"
    )

  // --- post-planning step (reviewed) on the planning chat ---

  /** `samplePlan` on a planning chat whose agent answers `reply`, after the
    * planning turn that opened its conversation.
    */
  private def planned(reply: CannedResult[Plan]): WithChat[Plan] =
    val chat = reply.agent.chat()
    val _ = chat.resultAs[Plan].autonomous.run("plan")
    WithChat(chat, samplePlan)

  test("reviewed returns the improved plan on the original chat binding"):
    val improved = samplePlan.copy(description = "tighter", brief = "sharper")
    val input = planned(new CannedResult(improved))
    val result = input.reviewed()
    assertEquals(result.value, improved)
    assert(
      result.chat eq input.chat,
      "reviewed must hand back the original chat, not the review sibling's"
    )

  test("reviewed continues the planning conversation read-only"):
    val reply = new CannedResult(samplePlan)
    val input = planned(reply)
    val _ = input.reviewed()
    assertEquals(reply.lastSession, Some(input.chat.id.value))
    assertEquals(reply.lastToolSet, Some(orca.agents.ToolSet.ReadOnly))

  test("reviewed runs its variant of the read-only agent"):
    val reply = new CannedResult(samplePlan)
    val onlyReads = orca.agents.AutoApprove.Only(Set("Read"))
    val _ = planned(reply).reviewed(variant = _.withAutoApprove(onlyReads))
    assertEquals(reply.lastAutoApprove, Some(onlyReads))
    assertEquals(reply.lastToolSet, Some(orca.agents.ToolSet.ReadOnly))
