package orca.plan

import orca.events.EventDispatcher
import orca.llm.SessionId

class AssessThenPlanTest extends munit.FunSuite:

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body"))
  )

  test("toVerdict maps verdict=proceed + plan to Verdict.Proceed"):
    val a = AssessedPlan(
      verdict = "proceed",
      plan = Some(samplePlan),
      rejectKind = None,
      rejectBody = None
    )
    assertEquals(a.toVerdict, Right(Verdict.Proceed(samplePlan)))

  test("toVerdict maps each reject kind to the matching Verdict.RejectionKind"):
    val kinds = List(
      "question" -> Verdict.RejectionKind.Question,
      "critique" -> Verdict.RejectionKind.Critique,
      "rebuff"   -> Verdict.RejectionKind.Rebuff
    )
    kinds.foreach: (wire, expected) =>
      val a = AssessedPlan(
        verdict = "reject",
        plan = None,
        rejectKind = Some(wire),
        rejectBody = Some(s"body for $wire")
      )
      assertEquals(
        a.toVerdict,
        Right(Verdict.Rejection(expected, s"body for $wire"))
      )

  test("toVerdict surfaces each malformed combination as a Left"):
    // One table covers every Left branch: missing plan on proceed, missing
    // rejectBody on reject, missing rejectKind on reject, unknown rejectKind
    // on reject, unknown top-level verdict.
    val cases = List[(AssessedPlan, String)](
      AssessedPlan("proceed", None, None, None) -> "no plan",
      AssessedPlan("reject", None, Some("question"), None) -> "no rejectBody",
      AssessedPlan("reject", None, None, Some("body")) -> "no rejectKind",
      AssessedPlan("reject", None, Some("nope"), Some("b")) -> "unknown rejectKind",
      AssessedPlan("maybe", None, None, None) -> "unknown verdict"
    )
    cases.foreach: (input, fragment) =>
      val msg = input.toVerdict.swap.getOrElse(
        fail(s"expected Left for $input")
      )
      assert(msg.contains(fragment), s"expected '$fragment' in '$msg'")

  test("Plan.autonomous.assessThenPlan returns the LLM's session id + verdict"):
    given orca.FlowContext = new orca.TestFlowContext(new EventDispatcher(Nil))
    val assessed = AssessedPlan(
      verdict = "reject",
      plan = None,
      rejectKind = Some("rebuff"),
      rejectBody = Some("duplicate of #42")
    )
    val (sid, verdict) = Plan.autonomous.assessThenPlan(
      "the report",
      new CannedAssessedPlanLlm(assessed)
    )
    assertEquals(SessionId.value(sid), "stub-sid")
    assertEquals(verdict, Verdict.Rejection(Verdict.RejectionKind.Rebuff, "duplicate of #42"))

  test("Plan.autonomous.assessThenPlan throws OrcaFlowException on malformed payload"):
    given orca.FlowContext = new orca.TestFlowContext(new EventDispatcher(Nil))
    val malformed = AssessedPlan("proceed", None, None, None)
    val ex = intercept[orca.OrcaFlowException]:
      Plan.autonomous.assessThenPlan(
        "the report",
        new CannedAssessedPlanLlm(malformed)
      )
    assert(ex.getMessage.contains("no plan"), ex.getMessage)
