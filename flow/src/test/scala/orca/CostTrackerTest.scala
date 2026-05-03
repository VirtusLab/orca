package orca

class CostTrackerTest extends munit.FunSuite:

  test("starts at zero and ignores non-TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.StageStarted("x"))
    tracker.onEvent(OrcaEvent.Step("hi"))
    assertEquals(tracker.total, Usage.empty)

  test("accumulates input and output tokens across multiple TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        "m1",
        Usage(100L, 50L, Some(BigDecimal("0.01")))
      )
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        "m1",
        Usage(30L, 20L, Some(BigDecimal("0.005")))
      )
    )
    assertEquals(
      tracker.total,
      Usage(130L, 70L, Some(BigDecimal("0.015")))
    )

  test("cost stays defined even when some events omit the cost"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        "m1",
        Usage(10L, 5L, Some(BigDecimal("0.002")))
      )
    )
    tracker.onEvent(OrcaEvent.TokensUsed("m1", Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, Some(BigDecimal("0.002")))

  test("cost is picked up when it first appears on a later event"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed("m1", Usage(10L, 5L, None)))
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        "m1",
        Usage(1L, 1L, Some(BigDecimal("0.003")))
      )
    )
    assertEquals(tracker.total.cost, Some(BigDecimal("0.003")))

  test("cost is None only when every event omits cost"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed("m1", Usage(10L, 5L, None)))
    tracker.onEvent(OrcaEvent.TokensUsed("m1", Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, None)

  test("summary lists each model's tokens, sorted by name, no costs"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed("m1", Usage(100L, 50L, Some(BigDecimal("0.0123"))))
    )
    tracker.onEvent(OrcaEvent.TokensUsed("a-model", Usage(7L, 3L, None)))
    assertEquals(
      tracker.summary,
      "a-model: 7 in, 3 out\nm1: 100 in, 50 out"
    )

  test("summary is empty when nothing has been recorded"):
    assertEquals(new CostTracker().summary, "")

  test("perModel breaks the running total down by model name"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed("haiku", Usage(10L, 5L, None))
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed("opus", Usage(20L, 15L, None))
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed("haiku", Usage(3L, 2L, None))
    )
    val breakdown = tracker.perModel
    assertEquals(breakdown("haiku"), Usage(13L, 7L, None))
    assertEquals(breakdown("opus"), Usage(20L, 15L, None))
    assertEquals(tracker.total, Usage(33L, 22L, None))

  test("calls without a pinned model fall back to the tool-name bucket"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed("claude", Usage(5L, 2L, None)))
    assertEquals(tracker.perModel("claude"), Usage(5L, 2L, None))
