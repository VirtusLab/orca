package orca.events

class UsageTest extends munit.FunSuite:

  test("+ adds every axis independently across usages from different backends"):
    // A run mixes backends: one reports both cache axes (claude/pi/opencode),
    // one only reads (codex/gemini), one neither. Every axis must add
    // independently, whatever order the events arrive in.
    val withWrites = Usage(
      inputTokens = 1_000L,
      outputTokens = 100L,
      cost = Some(BigDecimal("0.10")),
      cachedInputTokens = 600L,
      cacheWriteInputTokens = 300L
    )
    val readsOnly = Usage(
      inputTokens = 500L,
      outputTokens = 50L,
      cost = None,
      cachedInputTokens = 200L,
      reasoningOutputTokens = 20L
    )
    val plain = Usage(7L, 3L, Some(BigDecimal("0.01")))
    // Associativity alone would also hold for an implementation that kept one
    // side and dropped the other, so pin commutativity: it fails for both.
    assertEquals(
      (withWrites + readsOnly) + plain,
      withWrites + (readsOnly + plain)
    )
    assertEquals(readsOnly + withWrites, withWrites + readsOnly)
    assertEquals(
      (withWrites + readsOnly) + plain,
      Usage(
        inputTokens = 1_507L,
        outputTokens = 153L,
        cost = Some(BigDecimal("0.11")),
        cachedInputTokens = 800L,
        reasoningOutputTokens = 20L,
        cacheWriteInputTokens = 300L
      )
    )

  test("empty is the identity of +"):
    val u = Usage(10L, 5L, Some(BigDecimal("0.02")), 4L, 1L, 3L)
    assertEquals(Usage.empty + u, u)
    assertEquals(u + Usage.empty, u)
