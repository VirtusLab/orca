package orca.plan

import orca.plan.Title
import orca.agents.JsonData

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}

class PlanTest extends munit.FunSuite:

  // --- JSON — the structured-output / stage-result path ---

  test("Plan round-trips through JSON via the JsonData codec (brief included)"):
    val plan = Plan(
      epicId = "calculator-features",
      description = "Round out Calculator with the missing arithmetic ops.",
      tasks = List(
        Task(
          title = Title("Add multiply"),
          description = "Add a multiply(int a, int b) method to Calculator."
        ),
        Task(
          title = Title("Add divide"),
          description =
            "Add a divide(int, int) method with a zero-divisor guard."
        )
      ),
      brief = "Calculator lives in core/Calculator.scala; follow its style."
    )
    given codec
        : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[Plan] =
      summon[JsonData[Plan]].codec
    val json = writeToString(plan)
    val parsed = readFromString[Plan](json)
    assertEquals(parsed, plan)

  test("Announce[Plan] produces a header + per-task bullet summary"):
    val plan = Plan(
      epicId = "feat-pair",
      description = "",
      tasks = List(
        Task(Title("Add feature A"), "do A"),
        Task(Title("Add feature B"), "do B")
      ),
      brief = ""
    )
    val msg = summon[orca.agents.Announce[Plan]]
      .message(plan)
      .getOrElse(fail("expected a non-empty announce message"))
    assert(msg.startsWith("Planned 2 tasks:"))
    assert(msg.contains("- Add feature A"))
    assert(msg.contains("- Add feature B"))

  test("Announce[Plan] returns None for an empty plan (no Step emitted)"):
    assertEquals(
      summon[orca.agents.Announce[Plan]].message(Plan("empty", "", Nil, "")),
      None
    )

  // --- Markdown renderer (cosmetic summary; never parsed back, ADR 0018 §2.8) ---

  private val samplePlan = Plan(
    epicId = "add-divide-method",
    description = "Extend Calculator with safe integer division.",
    tasks = List(
      Task(Title("add-divide"), "Add a divide method."),
      Task(Title("add-divide-test"), "Add unit tests.")
    ),
    brief = "Build on core/Calculator.scala."
  )

  test("render emits the H1, description, and a section per task"):
    val md = Plan.render(samplePlan)
    assert(md.startsWith("# Plan: add-divide-method"), md)
    assert(md.contains("Extend Calculator"), md)
    assert(md.contains("## Task: add-divide\n\nAdd a divide method."), md)
    assert(md.contains("## Task: add-divide-test\n\nAdd unit tests."), md)

  test("render appends the brief as a trailing ## Brief section"):
    assert(
      Plan
        .render(samplePlan)
        .contains("## Brief\n\nBuild on core/Calculator.scala."),
      Plan.render(samplePlan)
    )

  test("render omits the ## Brief section when the brief is empty"):
    assert(!Plan.render(samplePlan.copy(brief = "")).contains("## Brief"))

  test("taskPrompt prepends the brief when non-empty"):
    val task = samplePlan.tasks.head
    assertEquals(
      samplePlan.copy(brief = "CONTEXT").taskPrompt(task),
      "CONTEXT\n\n---\n\nAdd a divide method."
    )

  test("taskPrompt returns description verbatim when brief is empty"):
    val task = samplePlan.tasks.head
    assertEquals(
      samplePlan.copy(brief = "").taskPrompt(task),
      "Add a divide method."
    )

  // --- JSON compat: pre-ADR-0018 stage logs carry a "completed" field ---

  test(
    "decoding a Task JSON payload with a legacy \"completed\" field skips it (JsonData's default jsoniter config, not overridden by strictCodecConfig)"
  ):
    given codec
        : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[Task] =
      summon[JsonData[Task]].codec
    val legacyJson =
      """{"title":"add-divide","description":"Add a divide method.","completed":true}"""
    assertEquals(
      readFromString[Task](legacyJson),
      Task(Title("add-divide"), "Add a divide method.")
    )
