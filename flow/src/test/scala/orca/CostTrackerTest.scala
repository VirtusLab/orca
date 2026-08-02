package orca

import orca.events.{
  Cost,
  CostTracker,
  ModelPricing,
  OrcaEvent,
  PriceList,
  Pricing,
  Usage
}
import orca.agents.Model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CostTrackerTest extends munit.FunSuite:

  private def tokens(
      agent: String,
      model: Option[String],
      u: Usage,
      role: Option[String] = None
  ): OrcaEvent.TokensUsed =
    OrcaEvent.TokensUsed(agent, model.map(Model.apply), u, role)

  // Tiny price list so token math gives round dollar figures: a model at
  // $1/M input means 1,000,000 input tokens = $1. Each of the four rates is
  // intentionally distinct so a test can tell which one was applied.
  private val testTable = PriceList(
    table = Map(
      Model("opus") -> ModelPricing(1, BigDecimal("0.10"), 5, 2),
      Model("haiku") -> ModelPricing(1, BigDecimal("0.10"), 5, 2)
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

  test("estimate bills cache reads at the read rate, not the input rate"):
    val tracker = new CostTracker(pricing = testTable)
    // A backend that reports one undifferentiated cache number (gemini) leaves
    // the write axis at zero, so nothing is billed twice:
    // 1M input total, 800k of which are cache reads:
    //   200k billable   @ $1/M    = $0.20
    //   800k cache read @ $0.10/M = $0.08
    //   no output                 = $0
    // Total: $0.28. A regression that drops the subtraction would bill
    // 1M @ $1/M = $1.00; one that swaps rates would bill 200k at the read rate.
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 1_000_000L,
          outputTokens = 0L,
          cost = None,
          cacheReadInputTokens = 800_000L
        )
      )
    )
    assertEquals(tracker.perAgentCost("claude").amount, BigDecimal("0.28"))

  test("estimate bills cache writes at their own rate, above base input"):
    // 1M input total: 600k cache reads, 300k cache writes, 100k fresh.
    //   100k fresh @ $1/M    = $0.10
    //   600k read  @ $0.10/M = $0.06
    //   300k write @ $2/M    = $0.60
    // Total: $0.76. Folding writes into reads (what the old single axis did)
    // would bill 900k @ $0.10/M = $0.09 and understate the turn by more
    // than half.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 1_000_000L,
          outputTokens = 0L,
          cost = None,
          cacheReadInputTokens = 600_000L,
          cacheWriteInputTokens = 300_000L
        )
      )
    )
    assertEquals(tracker.perAgentCost("claude").amount, BigDecimal("0.76"))

  test("cache axes over-reported past the input total never bill negatively"):
    // The invariant says reads + writes <= inputTokens; a backend that breaks
    // it must cost zero fresh input, not a negative charge that eats the rest
    // of the estimate. Here fresh would be -50k: 300k read @ $0.10/M +
    // 250k write @ $2/M = $0.53, with nothing subtracted for input. The two
    // cache counts differ so a read/write rate swap can't pass.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 500_000L,
          outputTokens = 0L,
          cost = None,
          cacheReadInputTokens = 300_000L,
          cacheWriteInputTokens = 250_000L
        )
      )
    )
    assertEquals(tracker.perAgentCost("claude").amount, BigDecimal("0.53"))

  test("a reported cost still renders a write-only cache parenthetical"):
    // A cold first turn writes the whole prompt and reads nothing, which is
    // the shape every fresh claude session starts with.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 30_000L,
          outputTokens = 500L,
          cost = Some(BigDecimal("0.42")),
          cacheWriteInputTokens = 29_000L
        )
      )
    )
    assert(
      tracker.summary.contains(
        "claude: 30K in (29K cache write), 500 out ($0.4200)"
      ),
      tracker.summary
    )

  test("shipped price list is re-checked at least twice a year"):
    // The rates are only as good as their last check against provider pricing
    // pages, and the legend advertises `lastUpdated` to users. Fail once the
    // snapshot is old enough that shipping it is a claim nobody verified.
    val age = ChronoUnit.DAYS
      .between(Pricing.default.lastUpdated, LocalDate.now())
    assert(
      age <= 183,
      s"the shipped pricing table was last checked ${age} days ago " +
        s"(${Pricing.default.lastUpdated}), past the 183-day limit.\n" +
        "To fix: re-check every row in `Pricing.default` " +
        "(flow/src/main/scala/orca/events/Pricing.scala) against the provider " +
        "pricing pages, correct any that moved, then set `lastUpdated` to " +
        "today.\nThis fires on elapsed time, not on a code change — an old " +
        "tag or commit built long after its release fails here with nothing " +
        "wrong in the code, and needs no fix unless you are shipping from it."
    )

  test("shipped table reproduces a captured turn's reported cost"):
    // Real turn from a claude run, reported by the CLI as $0.1876374. The
    // shipped rates must reproduce it: this is the one assertion that catches
    // a whole class of table errors — a wrong cache-write tier, a wrong base
    // rate, or a model id resolving to the wrong row (this id looks like a
    // Haiku snapshot but is served, and billed, as Sonnet 5).
    val tracker = new CostTracker
    tracker.onEvent(
      tokens(
        "reviewer",
        Some("claude-haiku-4-5-20251001"),
        Usage(
          inputTokens = 176_625L,
          outputTokens = 1_083L,
          cost = None,
          cacheReadInputTokens = 155_848L,
          cacheWriteInputTokens = 20_769L
        )
      )
    )
    assertEquals(
      tracker.perAgentCost("reviewer").amount,
      BigDecimal("0.1876374")
    )

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
    // "opus-mini" is a different (and differently-priced) model tier
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
    assert(out.contains("performance: 1M in, 0 out ($1.0000*)"), out)
    // Mixed → aggregate is estimated → prefix shifts; asterisk on the
    // amount drops since the word already says it.
    assert(out.contains("Estimated total: $1.1000"), out)
    assert(!out.contains("Estimated total: $1.1000*"), out)

  test("summary renders token counts compactly"):
    val cases = List(
      999L -> "999",
      1000L -> "1K", // boundary; whole values drop the ".0"
      103_800L -> "103.8K",
      3_200_000L -> "3.2M",
      999_950L -> "1M" // rounds up into the next unit
    )
    cases.foreach: (n, expected) =>
      val tracker = new CostTracker(pricing = testTable)
      tracker.onEvent(tokens("claude", Some("opus"), Usage(n, 0L, None)))
      assert(
        tracker.summary.contains(s"claude: $expected in"),
        tracker.summary
      )

  test("summary compacts the cache and reasoning parentheticals too"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "claude",
        Some("opus"),
        Usage(
          inputTokens = 50_000L,
          outputTokens = 12_500L,
          cost = None,
          cacheReadInputTokens = 40_000L,
          reasoningOutputTokens = 1_200L,
          cacheWriteInputTokens = 5_000L
        )
      )
    )
    assert(
      tracker.summary.contains(
        "claude: 50K in (40K cache read, 5K cache write), " +
          "12.5K out (1.2K reasoning)"
      ),
      tracker.summary
    )

  test("summary drops the cache-write part when a backend reports no writes"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens(
        "codex",
        Some("opus"),
        Usage(
          inputTokens = 50_000L,
          outputTokens = 100L,
          cost = None,
          cacheReadInputTokens = 40_000L
        )
      )
    )
    assert(
      tracker.summary.contains("codex: 50K in (40K cache read), 100 out"),
      tracker.summary
    )

  test("summary lists By agent lines alphabetically by their rendered label"):
    // Sorting on the raw agent name would slot an unprefixed agent between
    // role-prefixed ones (`reviewer: lint` < main < `reviewer: readability`
    // by name); the reader sees labels, so labels drive the order.
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(
      tokens("lint", Some("opus"), Usage(1L, 1L, None), role = Some("reviewer"))
    )
    tracker.onEvent(tokens("main", Some("opus"), Usage(1L, 1L, None)))
    tracker.onEvent(
      tokens(
        "readability",
        Some("opus"),
        Usage(1L, 1L, None),
        role = Some("reviewer")
      )
    )
    val out = tracker.summary
    val labels = List("main", "reviewer: lint", "reviewer: readability")
      .map(l => l -> out.indexOf(s"  $l:"))
    labels.foreach((l, i) => assert(i >= 0, s"missing agent line $l:\n$out"))
    assertEquals(
      labels.map(_._1),
      labels.sortBy(_._2).map(_._1),
      s"agent lines must be alphabetical by label; got:\n$out"
    )

  test("summary lists By model lines alphabetically by model label"):
    val tracker = new CostTracker(pricing = testTable)
    tracker.onEvent(tokens("a", Some("opus"), Usage(1L, 1L, None)))
    tracker.onEvent(tokens("b", Some("haiku"), Usage(1L, 1L, None)))
    tracker.onEvent(tokens("c", None, Usage(1L, 1L, None)))
    val out = tracker.summary
    val labels = List("(unknown)", "haiku", "opus")
      .map(l => l -> out.indexOf(s"  $l:"))
    labels.foreach((l, i) => assert(i >= 0, s"missing model line $l:\n$out"))
    assertEquals(
      labels.map(_._1),
      labels.sortBy(_._2).map(_._1),
      s"model lines must be alphabetical; got:\n$out"
    )

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
    // `agent` stays bare; the summary derives the "reviewer: <slug>" display
    // text purely from `role`, and adds a "By role:" subtotal section.
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
