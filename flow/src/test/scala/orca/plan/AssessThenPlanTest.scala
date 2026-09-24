package orca.plan

import orca.events.EventDispatcher
import orca.agents.ToolSet

class AssessThenPlanTest extends munit.FunSuite:
  import AssessedPlan.Decision.{Proceed, Reject}
  import Verdict.RejectionKind.{Question, Rebuff}

  // Planning helpers are gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body")),
    brief = "the brief"
  )

  test("toVerdict maps Proceed + plan to Verdict.Proceed"):
    val a = AssessedPlan(
      decision = Proceed,
      plan = Some(samplePlan),
      rejectKind = None,
      rejectBody = None
    )
    assertEquals(a.toVerdict, Right(Verdict.Proceed(samplePlan)))

  test("toVerdict maps Reject + kind + body to Verdict.Rejection"):
    val a = AssessedPlan(
      decision = Reject,
      plan = None,
      rejectKind = Some(Question),
      rejectBody = Some("which version?")
    )
    assertEquals(
      a.toVerdict,
      Right(Verdict.Rejection(Question, "which version?"))
    )

  test("toVerdict surfaces each malformed combination as a Left"):
    // One table covers every Left branch: missing plan on Proceed, missing
    // rejectBody or rejectKind on Reject.
    val cases = List[(AssessedPlan, String)](
      AssessedPlan(Proceed, None, None, None) -> "no plan",
      AssessedPlan(Reject, None, Some(Question), None) -> "no rejectBody",
      AssessedPlan(Reject, None, None, Some("body")) -> "no rejectKind"
    )
    cases.foreach: (input, fragment) =>
      val msg = input.toVerdict.swap.getOrElse(
        fail(s"expected Left for $input")
      )
      assert(msg.contains(fragment), s"expected '$fragment' in '$msg'")

  test("Plan.autonomous.assessThenPlan returns the parsed verdict"):
    given orca.FlowContext = new orca.TestFlowContext(new EventDispatcher(Nil))
    val assessed = AssessedPlan(
      decision = Reject,
      plan = None,
      rejectKind = Some(Rebuff),
      rejectBody = Some("duplicate of #42")
    )
    val canned = new CannedResult(assessed)
    val result = Plan.autonomous.assessThenPlan("the report", canned.agent)
    // The verdict is carried alongside the conversation that produced it.
    assertEquals(Some(result.chat.id.value), canned.lastSession)
    assertEquals(
      result.value,
      Verdict.Rejection(Rebuff, "duplicate of #42")
    )

  test("Plan.autonomous planner runs NetworkOnly (reads + read-only network)"):
    given orca.FlowContext = new orca.TestFlowContext(new EventDispatcher(Nil))
    val stub = new CannedResult(
      AssessedPlan(Proceed, Some(samplePlan), None, None)
    )
    val _ = Plan.autonomous.assessThenPlan("the report", stub.agent)
    assertEquals(stub.lastToolSet, Some(ToolSet.NetworkOnly))

  test(
    "Plan.autonomous.assessThenPlan throws OrcaFlowException on malformed payload"
  ):
    given orca.FlowContext = new orca.TestFlowContext(new EventDispatcher(Nil))
    val malformed = AssessedPlan(Proceed, None, None, None)
    val ex = intercept[orca.OrcaFlowException]:
      Plan.autonomous.assessThenPlan(
        "the report",
        new CannedResult(malformed).agent
      )
    assert(ex.getMessage.contains("no plan"), ex.getMessage)
