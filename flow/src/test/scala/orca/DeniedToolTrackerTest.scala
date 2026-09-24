package orca

import orca.events.{DeniedToolTracker, DeniedTools, OrcaEvent}
import ox.supervised

class DeniedToolTrackerTest extends munit.FunSuite:

  private val advice =
    "allow it in your harness settings, or relax the instruction that requests it"

  private def denied(tool: String, agent: String): OrcaEvent.ToolDenied =
    OrcaEvent.ToolDenied(tool, Some(agent))

  private def tally(events: List[OrcaEvent.ToolDenied]): DeniedTools =
    events.foldLeft(DeniedTools.empty)(_.add(_))

  test("summary is empty when nothing was denied"):
    assertEquals(DeniedTools.empty.summary, "")

  test("repeated denials of one tool count once per event, agents dedupe"):
    val tools = tally(
      List("review", "planning", "review")
        .map(denied("mcp__visdom__agents_md", _))
    )
    assertEquals(
      tools.summary,
      s"denied tool calls: mcp__visdom__agents_md ×3 (planning, review) — $advice"
    )

  test("tools order by count descending, then by name"):
    val tools = tally(
      List("b", "a", "c", "c").map(denied(_, "coding"))
    )
    assertEquals(
      tools.summary,
      List(
        s"denied tool calls: c ×2 (coding) — $advice",
        s"denied tool calls: a ×1 (coding) — $advice",
        s"denied tool calls: b ×1 (coding) — $advice"
      ).mkString("\n")
    )

  test("a denial without an agent name counts but adds no parenthetical"):
    val tools = tally(List(OrcaEvent.ToolDenied("Write", None)))
    assertEquals(tools.summary, s"denied tool calls: Write ×1 — $advice")

  test("the tracker counts only ToolDenied events"):
    val summary = supervised:
      val tracker = DeniedToolTracker.start()
      List(
        OrcaEvent.Step("hi"),
        OrcaEvent.ToolUse("Bash", "ls", Some("review")),
        denied("Bash", "review"),
        denied("Bash", "review")
      ).foreach(tracker.onEvent)
      tracker.summary
    assertEquals(summary, s"denied tool calls: Bash ×2 (review) — $advice")
