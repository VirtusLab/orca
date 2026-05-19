package orca

import orca.events.{
  CostTracker,
  EventDispatcher,
  OrcaEvent,
  OrcaListener,
  Usage
}

class CostTrackerTest extends munit.FunSuite:

  private def tokens(
      agent: String,
      model: Option[String],
      u: Usage
  ): OrcaEvent.TokensUsed = OrcaEvent.TokensUsed(agent, model, u)

  test("starts at zero and ignores non-TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.StageStarted("x"))
    tracker.onEvent(OrcaEvent.Step("hi"))
    assertEquals(tracker.total, Usage.empty)

  test("total sums every TokensUsed event regardless of agent or model"):
    val tracker = new CostTracker
    tracker.onEvent(tokens("claude", Some("opus"), Usage(100L, 50L, None)))
    tracker.onEvent(tokens("performance", Some("haiku"), Usage(30L, 20L, None)))
    assertEquals(tracker.total, Usage(130L, 70L, None))

  test("perAgent groups by LlmTool name"):
    val tracker = new CostTracker
    tracker.onEvent(tokens("claude", Some("opus"), Usage(10L, 5L, None)))
    tracker.onEvent(tokens("performance", Some("opus"), Usage(20L, 15L, None)))
    tracker.onEvent(tokens("claude", Some("haiku"), Usage(3L, 2L, None)))
    assertEquals(tracker.perAgent("claude"), Usage(13L, 7L, None))
    assertEquals(tracker.perAgent("performance"), Usage(20L, 15L, None))

  test("perModel groups by reported model id, with None as its own bucket"):
    val tracker = new CostTracker
    tracker.onEvent(tokens("claude", Some("opus"), Usage(10L, 5L, None)))
    tracker.onEvent(tokens("performance", Some("opus"), Usage(20L, 15L, None)))
    tracker.onEvent(tokens("claude", None, Usage(3L, 2L, None)))
    assertEquals(tracker.perModel(Some("opus")), Usage(30L, 20L, None))
    assertEquals(tracker.perModel(None), Usage(3L, 2L, None))

  test("summary renders both axes; missing models surface as `(unknown)`"):
    val tracker = new CostTracker
    tracker.onEvent(tokens("claude", Some("opus"), Usage(10L, 5L, None)))
    tracker.onEvent(tokens("performance", None, Usage(7L, 3L, None)))
    val expected =
      """By agent:
        |  claude: 10 in, 5 out
        |  performance: 7 in, 3 out
        |
        |By model:
        |  (unknown): 7 in, 3 out
        |  opus: 10 in, 5 out""".stripMargin
    assertEquals(tracker.summary, expected)

  test("summary is empty when nothing has been recorded"):
    assertEquals(new CostTracker().summary, "")
