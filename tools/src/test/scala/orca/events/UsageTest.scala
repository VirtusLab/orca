package orca.events

class UsageTest extends munit.FunSuite:

  test("+ adds every axis independently across usages from different backends"):
    // A run mixes backends reporting different axis subsets. Every axis must
    // add independently, whatever order the events arrive in.
    val withWrites = Usage(
      inputTokens = 1_000L,
      outputTokens = 100L,
      cost = Some(BigDecimal("0.10")),
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      apiCalls = Some(2L)
    )
    val fewerWrites = Usage(
      inputTokens = 500L,
      outputTokens = 50L,
      cost = None,
      cacheReadInputTokens = 200L,
      reasoningOutputTokens = 20L,
      // non-zero, and different from `withWrites`, so a keep-one-side `+`
      // can't produce the expected total on this axis either
      cacheWriteInputTokens = 90L,
      // Unset, as a backend that cannot count its API calls leaves it — the
      // total below must still keep the two counts that were measured.
      apiCalls = None
    )
    val plain =
      Usage(7L, 3L, Some(BigDecimal("0.01")), apiCalls = Some(5L))
    assertEquals(
      (withWrites + fewerWrites) + plain,
      Usage(
        inputTokens = 1_507L,
        outputTokens = 153L,
        cost = Some(BigDecimal("0.11")),
        cacheReadInputTokens = 800L,
        reasoningOutputTokens = 20L,
        cacheWriteInputTokens = 390L,
        apiCalls = Some(7L)
      )
    )
    assertEquals(fewerWrites + withWrites, withWrites + fewerWrites)

  test("empty is the identity of +"):
    val u = Usage(10L, 5L, Some(BigDecimal("0.02")), 4L, 1L, 3L, Some(2L))
    assertEquals(Usage.empty + u, u)
    assertEquals(u + Usage.empty, u)
