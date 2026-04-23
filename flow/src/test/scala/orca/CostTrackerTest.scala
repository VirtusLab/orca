package orca

class CostTrackerTest extends munit.FunSuite:

  test("starts at zero and ignores non-TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.StageStarted("x"))
    tracker.onEvent(OrcaEvent.LlmOutput("hi"))
    assertEquals(tracker.total, Usage.empty)

  test("accumulates input and output tokens across multiple TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        Some("m1"),
        Usage(100L, 50L, Some(BigDecimal("0.01")))
      )
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        Some("m1"),
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
        Some("m1"),
        Usage(10L, 5L, Some(BigDecimal("0.002")))
      )
    )
    tracker.onEvent(OrcaEvent.TokensUsed(Some("m1"), Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, Some(BigDecimal("0.002")))

  test("cost is picked up when it first appears on a later event"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed(Some("m1"), Usage(10L, 5L, None)))
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        Some("m1"),
        Usage(1L, 1L, Some(BigDecimal("0.003")))
      )
    )
    assertEquals(tracker.total.cost, Some(BigDecimal("0.003")))

  test("cost is None only when every event omits cost"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed(Some("m1"), Usage(10L, 5L, None)))
    tracker.onEvent(OrcaEvent.TokensUsed(Some("m1"), Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, None)

  test("summary includes token totals and cost when present"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(
        Some("m1"),
        Usage(100L, 50L, Some(BigDecimal("0.0123")))
      )
    )
    val s = tracker.summary
    assert(s.contains("100 in"))
    assert(s.contains("50 out"))
    assert(s.contains("0.0123"))

  test("perModel breaks the running total down by model name"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(Some("haiku"), Usage(10L, 5L, None))
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed(Some("opus"), Usage(20L, 15L, None))
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed(Some("haiku"), Usage(3L, 2L, None))
    )
    val breakdown = tracker.perModel
    assertEquals(breakdown(Some("haiku")), Usage(13L, 7L, None))
    assertEquals(breakdown(Some("opus")), Usage(20L, 15L, None))
    assertEquals(tracker.total, Usage(33L, 22L, None))

  test("events without a model fold under the None key"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed(None, Usage(5L, 2L, None)))
    assertEquals(tracker.perModel(None), Usage(5L, 2L, None))
