package orca.plan

import orca.plan.Title
import orca.llm.JsonData

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}

class PlanTest extends munit.FunSuite:

  private val sample =
    """# Plan: add-divide-method
      |
      |Extend Calculator with safe integer division. The current API
      |covers add/subtract/multiply but not divide, and callers have
      |started rolling their own with inconsistent zero-handling.
      |
      |## Task: add-divide
      |Status: [ ]
      |
      |Add a `divide(int a, int b)` method to Calculator that returns
      |`a / b` and throws `IllegalArgumentException` for `b == 0`.
      |
      |## Task: add-divide-test
      |Status: [x]
      |
      |Add unit tests covering the happy path and the zero-divisor
      |case.
      |
      |## Brief
      |
      |Build on the existing Calculator helper in core/Calculator.scala.
      |""".stripMargin

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
    val msg = summon[orca.llm.Announce[Plan]]
      .message(plan)
      .getOrElse(fail("expected a non-empty announce message"))
    assert(msg.startsWith("Planned 2 tasks on branch 'feat-pair'"))
    assert(msg.contains("- Add feature A"))
    assert(msg.contains("- Add feature B"))

  test("Announce[Plan] returns None for an empty plan (no Step emitted)"):
    assertEquals(
      summon[orca.llm.Announce[Plan]].message(Plan("empty", "", Nil, "")),
      None
    )

  // --- Markdown parser / renderer — cosmetic checklist round-trip ---

  test("parse extracts the branch name from the H1"):
    assertEquals(Plan.parse(sample).epicId, "add-divide-method")

  test("parse extracts the epic description between the H1 and the first task"):
    val description = Plan.parse(sample).description
    assert(description.startsWith("Extend Calculator"))
    assert(description.contains("inconsistent zero-handling"))
    // The description must not bleed into the first task block.
    assert(!description.contains("## Task"))

  test("parse yields an empty description when the file has no preamble"):
    val noPreamble =
      """# Plan: x
        |
        |## Task: t
        |Status: [ ]
        |
        |body
        |""".stripMargin
    assertEquals(Plan.parse(noPreamble).description, "")

  test("parse splits the file into tasks and reads each status checkbox"):
    val plan = Plan.parse(sample)
    assertEquals(plan.tasks.size, 2)
    assertEquals(plan.tasks.head.title, Title("add-divide"))
    assertEquals(plan.tasks.head.completed, false)
    assertEquals(plan.tasks(1).title, Title("add-divide-test"))
    assertEquals(plan.tasks(1).completed, true)

  test("parse keeps the multi-line description body intact"):
    val description = Plan.parse(sample).tasks.head.description
    assert(description.startsWith("Add a `divide"))
    assert(description.contains("IllegalArgumentException"))

  test("render + parse round-trips the plan (brief included)"):
    val original = Plan.parse(sample)
    assert(original.brief.contains("Calculator helper"))
    assertEquals(Plan.parse(Plan.render(original)), original)

  // --- the trailing ## Brief section ---

  private val samplePlan =
    Plan("epic", "desc", List(Task(Title("t"), "implement t")), "")

  test("parse reads the trailing ## Brief section into the brief field"):
    assertEquals(
      Plan.parse(sample).brief,
      "Build on the existing Calculator helper in core/Calculator.scala."
    )

  test("parse without a ## Brief section yields an empty brief"):
    val noBrief =
      """# Plan: x
        |
        |## Task: t
        |Status: [ ]
        |
        |body
        |""".stripMargin
    assertEquals(Plan.parse(noBrief).brief, "")

  test("render + parse round-trips a plan with a non-empty brief"):
    val plan = samplePlan.copy(brief = "Build on bar/Baz.scala.")
    assertEquals(Plan.parse(Plan.render(plan)), plan)

  test("a literal ## Brief line in the description does not swallow the tasks"):
    val tricky =
      """# Plan: x
        |
        |Context.
        |## Brief
        |more context
        |
        |## Task: t
        |Status: [ ]
        |
        |body
        |""".stripMargin
    val plan = Plan.parse(tricky)
    assertEquals(plan.tasks.size, 1)
    assert(plan.description.contains("## Brief"))

  test("a brief is kept verbatim even if it contains ## Task lines"):
    // The brief is split off before task parsing, so its markdown can't be
    // mistaken for plan tasks.
    val brief = "## Task: not a real task\nsome notes"
    val parsed = Plan.parse(Plan.render(samplePlan.copy(brief = brief)))
    assertEquals(parsed.tasks.size, 1)
    assertEquals(parsed.brief, brief)

  test("taskPrompt prepends the brief"):
    val task = samplePlan.tasks.head
    assertEquals(
      samplePlan.copy(brief = "CONTEXT").taskPrompt(task),
      "CONTEXT\n\n---\n\nimplement t"
    )

  test("markComplete flips one task's checkbox without touching others"):
    val plan = Plan.parse(sample)
    val updated = plan.markComplete(Title("add-divide"))
    assertEquals(updated.tasks.head.completed, true)
    assertEquals(updated.tasks(1).completed, true)
    // markComplete on a title that doesn't exist is a no-op.
    assertEquals(plan.markComplete(Title("ghost")), plan)

  test("firstIncomplete returns the first task with [ ] in declaration order"):
    val plan = Plan.parse(sample)
    assertEquals(plan.firstIncomplete.map(_.title), Some(Title("add-divide")))
    assertEquals(plan.markComplete(Title("add-divide")).firstIncomplete, None)

  test("parse throws on a missing # Plan header"):
    intercept[PlanParseException]:
      Plan.parse("## Task: orphan\nStatus: [ ]\n\nbody\n")

  test("parse throws on a task missing the Status line"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |
        |body
        |""".stripMargin
    intercept[PlanParseException](Plan.parse(bad))

  test("parse throws on an unrecognised status checkbox"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |Status: [?]
        |
        |body
        |""".stripMargin
    intercept[PlanParseException](Plan.parse(bad))

  test("parse throws on a plan with no tasks"):
    intercept[PlanParseException](Plan.parse("# Plan: empty\n"))

  test("parse normalises CRLF line endings and a leading BOM"):
    val crlf = sample.replace("\n", "\r\n")
    val plan = Plan.parse("﻿" + crlf)
    assertEquals(plan.epicId, "add-divide-method")
    assertEquals(plan.tasks.size, 2)

  test("parse throws on a task with empty prompt"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |Status: [ ]
        |""".stripMargin
    intercept[PlanParseException](Plan.parse(bad))
