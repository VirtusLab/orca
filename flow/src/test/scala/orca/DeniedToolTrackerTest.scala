package orca

import orca.events.{DeniedToolTracker, OrcaEvent}
import ox.{fork, supervised}

class DeniedToolTrackerTest extends munit.FunSuite:

  private val advice =
    "allow it in your harness settings, or relax the instruction that requests it"

  private def denied(tool: String, agent: String): OrcaEvent.ToolDenied =
    OrcaEvent.ToolDenied(tool, Some(agent))

  test("summary is empty when only non-denial events were recorded"):
    val tracker = new DeniedToolTracker()
    tracker.onEvent(OrcaEvent.Step("hi"))
    tracker.onEvent(OrcaEvent.ToolUse("Bash", "ls", Some("coding")))
    assertEquals(tracker.summary, "")

  test("repeated denials of one tool count once per event, agents dedupe"):
    val tracker = new DeniedToolTracker()
    List("review", "planning", "review")
      .foreach(a => tracker.onEvent(denied("mcp__visdom__agents_md", a)))
    assertEquals(
      tracker.summary,
      s"denied tool calls: mcp__visdom__agents_md ×3 (planning, review) — $advice"
    )

  test("tools order by count descending, then by name"):
    val tracker = new DeniedToolTracker()
    List("b" -> "coding", "a" -> "coding", "c" -> "coding", "c" -> "coding")
      .foreach((tool, agent) => tracker.onEvent(denied(tool, agent)))
    assertEquals(
      tracker.summary,
      List(
        s"denied tool calls: c ×2 (coding) — $advice",
        s"denied tool calls: a ×1 (coding) — $advice",
        s"denied tool calls: b ×1 (coding) — $advice"
      ).mkString("\n")
    )

  test("a denial without an agent name counts but adds no parenthetical"):
    val tracker = new DeniedToolTracker()
    tracker.onEvent(OrcaEvent.ToolDenied("Write", None))
    assertEquals(tracker.summary, s"denied tool calls: Write ×1 — $advice")

  test("concurrent denials lose no counts"):
    val tracker = new DeniedToolTracker()
    supervised:
      val forks = (1 to 8).map: i =>
        fork:
          (1 to 100).foreach(_ => tracker.onEvent(denied("Bash", s"r$i")))
      forks.foreach(_.join())
    assert(
      tracker.summary.startsWith("denied tool calls: Bash ×800 ("),
      tracker.summary
    )
