package orca.plan

import orca.Title

class ExtendedPlanTest extends munit.FunSuite:

  private val sample =
    """# Plan: add-divide-method
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

  test("parse extracts the branch name from the H1"):
    val plan = ExtendedPlan.parse(sample)
    assertEquals(plan.epicId, "add-divide-method")

  test("parse splits the file into tasks and reads each status checkbox"):
    val plan = ExtendedPlan.parse(sample)
    assertEquals(plan.tasks.size, 2)
    assertEquals(plan.tasks.head.title, Title("add-divide"))
    assertEquals(plan.tasks.head.completed, false)
    assertEquals(plan.tasks(1).title, Title("add-divide-test"))
    assertEquals(plan.tasks(1).completed, true)

  test("parse keeps the multi-line description body intact"):
    val plan = ExtendedPlan.parse(sample)
    val description = plan.tasks.head.description
    assert(description.startsWith("Add a `divide"), s"got: $description")
    assert(
      description.contains("IllegalArgumentException"),
      s"got: $description"
    )

  test("render + parse round-trips the plan"):
    val original = ExtendedPlan.parse(sample)
    val redacted = ExtendedPlan.parse(ExtendedPlan.render(original))
    assertEquals(redacted, original)

  test("markComplete flips one task's checkbox without touching others"):
    val plan = ExtendedPlan.parse(sample)
    val updated = plan.markComplete(Title("add-divide"))
    assertEquals(updated.tasks.head.completed, true)
    assertEquals(updated.tasks(1).completed, true)
    // markComplete on a title that doesn't exist is a no-op (caller decides).
    assertEquals(plan.markComplete(Title("ghost")), plan)

  test("firstIncomplete returns the first task with [ ] in declaration order"):
    val plan = ExtendedPlan.parse(sample)
    assertEquals(plan.firstIncomplete.map(_.title), Some(Title("add-divide")))
    val complete = plan.markComplete(Title("add-divide"))
    assertEquals(complete.firstIncomplete, None)

  test("parse throws on a missing # Plan header"):
    intercept[PlanParseException]:
      ExtendedPlan.parse("## Task: orphan\nStatus: [ ]\n\nbody\n")

  test("parse throws on a task missing the Status line"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |
        |body
        |""".stripMargin
    intercept[PlanParseException](ExtendedPlan.parse(bad))

  test("parse throws on an unrecognised status checkbox"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |Status: [?]
        |
        |body
        |""".stripMargin
    intercept[PlanParseException](ExtendedPlan.parse(bad))

  test("parse throws on a plan with no tasks"):
    intercept[PlanParseException](ExtendedPlan.parse("# Plan: empty\n"))

  test("parse normalises CRLF line endings and a leading BOM"):
    val crlf = sample.replace("\n", "\r\n")
    val withBom = "﻿" + crlf
    val plan = ExtendedPlan.parse(withBom)
    assertEquals(plan.epicId, "add-divide-method")
    assertEquals(plan.tasks.size, 2)

  test("parse throws on a task with empty prompt"):
    val bad =
      """# Plan: x
        |
        |## Task: t
        |Status: [ ]
        |""".stripMargin
    intercept[PlanParseException](ExtendedPlan.parse(bad))

  test("loadOrGenerate parses and reuses an existing file (no LLM call)"):
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[orca.OrcaEvent]](Nil)
    val listener = new orca.OrcaListener:
      def onEvent(event: orca.OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.EventDispatcher(List(listener))
    )
    val tmp = os.temp(suffix = ".md")
    os.write.over(tmp, sample)
    val llm =
      new ExplodingLlm("loadOrGenerate must not call ask when file exists")
    val plan = ExtendedPlan.loadOrGenerate(tmp, "ignored", llm)
    assertEquals(plan.epicId, "add-divide-method")
    assert(
      seen.get().exists {
        case orca.OrcaEvent.Step(msg) => msg.contains("Reusing existing plan")
        case _                        => false
      },
      s"expected a 'Reusing' Step; got: ${seen.get()}"
    )

  test("loadOrGenerate writes a new file when none exists"):
    given orca.FlowContext =
      new orca.TestFlowContext(new orca.EventDispatcher(Nil))
    val dir = os.temp.dir()
    val target = dir / "dev.md"
    val canned = sample
    val llm = new CannedLlm(canned)
    val plan = ExtendedPlan.loadOrGenerate(target, "Add a divide method", llm)
    assert(os.exists(target))
    assertEquals(plan.epicId, "add-divide-method")
    val onDisk = ExtendedPlan.parse(os.read(target))
    assertEquals(onDisk, plan)

  test("persistComplete updates the on-disk plan"):
    val tmp = os.temp(suffix = ".md")
    os.write.over(tmp, sample)
    ExtendedPlan.persistComplete(tmp, Title("add-divide"))
    val reread = ExtendedPlan.parse(os.read(tmp))
    assertEquals(reread.tasks.head.completed, true)
    assertEquals(reread.tasks(1).completed, true)
