package orca.plan

import orca.plan.Title
import orca.llm.{JsonData}
import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  Usage
}

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
      |""".stripMargin

  // --- JSON / Announce — covers the in-memory `Plan.from` path ---

  test("Plan round-trips through JSON via the JsonData codec"):
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
      )
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
      )
    )
    val msg = summon[orca.llm.Announce[Plan]]
      .message(plan)
      .getOrElse(fail("expected a non-empty announce message"))
    assert(msg.startsWith("Planned 2 tasks on branch 'feat-pair'"))
    assert(msg.contains("- Add feature A"))
    assert(msg.contains("- Add feature B"))

  test("Announce[Plan] returns None for an empty plan (no Step emitted)"):
    assertEquals(
      summon[orca.llm.Announce[Plan]].message(Plan("empty", "", Nil)),
      None
    )

  // --- Markdown parser / renderer — covers the persisted path ---

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

  test("render + parse round-trips the plan"):
    val original = Plan.parse(sample)
    assertEquals(Plan.parse(Plan.render(original)), original)

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

  // --- autonomous.loadOrGenerate / persistComplete — resume path ---

  test(
    "autonomous.loadOrGenerate parses and reuses an existing file (no LLM call)"
  ):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[
        orca.events.OrcaEvent
      ]](Nil)
    val listener = new orca.events.OrcaListener:
      def onEvent(event: orca.events.OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.events.EventDispatcher(List(listener))
    )
    val tmp = os.temp(suffix = ".md")
    os.write.over(tmp, sample)
    val llm =
      new ExplodingLlm("loadOrGenerate must not call the LLM when file exists")
    val plan = Plan.autonomous.loadOrGenerate(tmp, "ignored", llm)
    assertEquals(plan.epicId, "add-divide-method")
    assert(
      seen.get().exists {
        case orca.events.OrcaEvent.Step(msg) =>
          msg.contains("Reusing existing plan")
        case _ => false
      }
    )

  test("autonomous.loadOrGenerate writes a new file when none exists"):
    given orca.FlowContext =
      new orca.TestFlowContext(new orca.events.EventDispatcher(Nil))
    val target = os.temp.dir() / "dev.md"
    val expected = Plan.parse(sample)
    val plan = Plan.autonomous.loadOrGenerate(
      target,
      "Add a divide method",
      new CannedPlanLlm(expected)
    )
    assert(os.exists(target))
    assertEquals(plan, expected)
    assertEquals(Plan.parse(os.read(target)), expected)

  test("persistComplete updates the on-disk plan"):
    val tmp = os.temp(suffix = ".md")
    os.write.over(tmp, sample)
    Plan.persistComplete(tmp, Title("add-divide"))
    val reread = Plan.parse(os.read(tmp))
    assertEquals(reread.tasks.head.completed, true)
    assertEquals(reread.tasks(1).completed, true)
