package orca

import orca.events.{
  Cost,
  CostTracker,
  ModelPricing,
  OrcaEvent,
  PriceList,
  Usage
}
import orca.agents.Model

import java.time.LocalDate

class CostTrackerTest extends munit.FunSuite:

  private def tokens(
      agent: String,
      model: Option[String],
      u: Usage,
      role: Option[String] = None
  ): OrcaEvent.TokensUsed =
    OrcaEvent.TokensUsed(agent, model.map(Model.apply), u, role)

  // Tiny price list so token math gives round dollar figures: a model at
  // $1/M input means 1,000,000 input tokens = $1. `cachedInputUsdPerMillion`
  // is intentionally distinct from `inputUsdPerMillion` so tests can tell
  // the two rates apart.
  private val testTable = PriceList(
    table = Map(
      Model("opus") -> ModelPricing(1, BigDecimal("0.10"), 5),
      Model("haiku") -> ModelPricing(1, BigDecimal("0.10"), 5)
    ),
    lastUpdated = LocalDate.of(2026, 1, 15)
  )

  test("starts at zero and ignores non-TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.StageStarted("x"))
    tracker.onEvent(OrcaEvent.Step("hi"))
    assertEquals(tracker.total, Usage.empty)
    assertEquals(tracker.totalCost, None)

  test("total sums every TokensUsed event regardless of agent or model"):
    val tracker = new CostTracker
    tracker.onEvent(tokens("claude", Some("opus"), Usage(100L, 50L, None)))
    tracker.onEvent(tokens("performance", Some("haiku"), Usage(30L, 20L, None)))
    assertEquals(tracker.total, Usage(130L, 70L, None))

  test("perAgent groups by Agent name"):
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
    assertEquals(tracker.perModel(Some(Model("opus"))), Usage(30L, 20L, None))
    assertEquals(tracker.perModel(None), Usage(3L, 2L, None))

  test("reported cost from the backend is accumulated as non-estimated"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(100L, 50L, Some(BigDecimal("0.42"))))
    )
    assertEquals(
      tracker.perAgentCost("claude"),
      Cost(BigDecimal("0.42"), estimated = false)
    )

  test("missing reported cost falls back to a price-table estimate"):
    val tracker = new CostTracker(pricing = testTable)
    // 1M input @ $1/M + 500k output @ $5/M = $3.50
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(1_000_000L, 500_000L, None))
    )
    val c = tracker.perAgentCost("claude")
    assertEquals(c.estimated, true)
    assertEquals(c.amount, BigDecimal("3.5"))

  test("estimate bills cached input at the cached rate, not the input rate"):
    val tracker = new CostTracker(pricing = testTable)
    // 1M input total, 800k of which are cached:
    //   200k billable @ $1/M  = $0.20
    //   800k cached   @ $0.10/M = $0.08
    //   no output             = $0
    // Total: $0.28. A regression that drops the subtraction would bill
    // 1M @ $1/M = $1.00; one that swaps rates would bill 200k cached.
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 1_000_000L,
          outputTokens = 0L,
          cost = None,
          cachedInputTokens = 800_000L
        )
      )
    )
    assertEquals(tracker.perAgentCost("claude").amount, BigDecimal("0.28"))

  test("estimate ignores reasoning tokens (already inside output)"):
    val tracker = new CostTracker(pricing = testTable)
    // Pin the invariant: reasoning is a sub-portion of outputTokens, not
    // an additional billable bucket. Adding 400k reasoning should leave
    // the estimate unchanged at 1M output @ $5/M = $5.00.
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 0L,
          outputTokens = 1_000_000L,
          cost = None,
          reasoningOutputTokens = 400_000L
        )
      )
    )
    assertEquals(tracker.perAgentCost("claude").amount, BigDecimal("5.0"))

  test("price-table lookup falls back to a prefix match"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus-20251015"),
        Usage(1_000_000L, 0L, None)
      )
    )
    val c = tracker.perAgentCost("claude")
    assertEquals(c.estimated, true)
    assertEquals(c.amount, BigDecimal("1.0"))

  test(
    "price-table lookup does NOT fall back across a non-date model-tier suffix"
  ):
    // 12.5: "opus-mini" is a different (and differently-priced) model tier
    // from "opus", not a dated snapshot of it — unlike "opus-20251015" above,
    // the prefix fallback must not cross tiers just because one name prefixes
    // the other. Mirrors the real-world gemini-2.5-flash / -flash-lite risk.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("claude", Some("opus-mini"), Usage(1_000_000L, 0L, None))
    )
    assert(
      tracker.perAgentCost.get("claude").isEmpty,
      s"expected no cost estimate for an unrelated tier; got: ${tracker.perAgentCost
          .get("claude")}"
    )

  test("mixed reported + estimated rolls up to an estimated aggregate"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(0L, 0L, Some(BigDecimal("1.0"))))
    )
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(1_000_000L, 0L, None))
    )
    val c = tracker.totalCost.get
    assertEquals(c.estimated, true)
    assertEquals(c.amount, BigDecimal("2.0"))

  test(
    "summary formats per-line cost as $X.XXXX with an asterisk on estimates"
  ):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(10L, 5L, Some(BigDecimal("0.10"))))
    )
    tracker.onEvent(
      tokens("performance", Some("haiku"), Usage(1_000_000L, 0L, None))
    )
    val out = tracker.summary
    assert(out.contains("claude: 10 in, 5 out ($0.1000)"), out)
    assert(out.contains("performance: 1000000 in, 0 out ($1.0000*)"), out)
    // Mixed → aggregate is estimated → prefix shifts; asterisk on the
    // amount drops since the word already says it.
    assert(out.contains("Estimated total: $1.1000"), out)
    assert(!out.contains("Estimated total: $1.1000*"), out)

  test("summary's estimate legend cites the price-list lastUpdated date"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("performance", Some("haiku"), Usage(1_000_000L, 0L, None))
    )
    assert(tracker.summary.contains("rates as of 2026-01-15"), tracker.summary)

  test("summary omits the legend when every line was reported"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("claude", Some("opus"), Usage(10L, 5L, Some(BigDecimal("0.10"))))
    )
    val out = tracker.summary
    assert(!out.contains("*"), out)
    assert(!out.contains("estimated"), out)
    assert(out.contains("Total: $0.1000"), out)

  test("perRole subtotals usage by role, with None as the untagged bucket"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "performance",
        Some("opus"),
        Usage(10L, 5L, None),
        role = Some("reviewer")
      )
    )
    tracker.onEvent(
      tokens(
        "security",
        Some("opus"),
        Usage(20L, 5L, None),
        role = Some("reviewer")
      )
    )
    tracker.onEvent(tokens("claude", Some("opus"), Usage(100L, 50L, None)))
    assertEquals(
      tracker.perRole(Some("reviewer")),
      Usage(30L, 10L, None)
    )
    assertEquals(tracker.perRole(None), Usage(100L, 50L, None))

  test(
    "summary derives a display-only role prefix per agent and adds a By role subtotal section"
  ):
    // 12.7: the old "reviewer: <slug>" string was baked into the agent's
    // identity/name; now `agent` stays bare and the summary derives the same
    // display text purely from `role`, plus a "By role:" subtotal section.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "performance",
        Some("opus"),
        Usage(10L, 5L, None),
        role = Some("reviewer")
      )
    )
    tracker.onEvent(tokens("claude", Some("opus"), Usage(100L, 50L, None)))
    val out = tracker.summary
    assert(
      out.contains("reviewer: performance:"),
      s"expected a role-derived display prefix; got: $out"
    )
    assert(
      out.contains("  claude:"),
      s"claude's line must stay bare; got: $out"
    )
    assert(out.contains("By role:"), out)
    assert(out.contains("  reviewer:"), out)

  test("summary omits the By role section when no event carried a role"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(tokens("claude", Some("opus"), Usage(10L, 5L, None)))
    assert(!tracker.summary.contains("By role:"), tracker.summary)

  test("summary is empty when nothing has been recorded"):
    assertEquals(new CostTracker().summary, "")
