package orca.events

class UsageTest extends munit.FunSuite:

  test("+ adds every axis independently across usages from different backends"):
    // A run mixes backends reporting different axis subsets. Every axis must
    // add independently, whatever order the events arrive in.
    val withWrites = Usage.exclusiveInput(
      freshInputTokens = 100L,
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      outputTokens = 100L,
      reasoningOutputTokens = 0L,
      cost = Some(BigDecimal("0.10")),
      apiCalls = Some(2L)
    )
    val fewerWrites = Usage.exclusiveInput(
      freshInputTokens = 210L,
      cacheReadInputTokens = 200L,
      // non-zero, and different from `withWrites`, so a keep-one-side `+`
      // can't produce the expected total on this axis either
      cacheWriteInputTokens = 90L,
      outputTokens = 50L,
      reasoningOutputTokens = 20L,
      cost = None,
      // Unset, as a backend that cannot count its API calls leaves it — the
      // total below must still keep the two counts that were measured.
      apiCalls = None
    )
    val plain = Usage.exclusiveInput(
      freshInputTokens = 7L,
      cacheReadInputTokens = 0L,
      cacheWriteInputTokens = 0L,
      outputTokens = 3L,
      reasoningOutputTokens = 0L,
      cost = Some(BigDecimal("0.01")),
      apiCalls = Some(5L)
    )
    assertEquals(
      (withWrites + fewerWrites) + plain,
      Usage(
        freshInputTokens = 317L,
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

  test("inputTokens totals the three disjoint input axes"):
    val u = Usage(10L, 0L, None, 4L, 0L, 3L, None)
    assertEquals(u.inputTokens, 17L)

  test("inclusiveInput splits the wire total into its fresh remainder"):
    val u = Usage.inclusiveInput(
      totalInputTokens = 1_000L,
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      outputTokens = 0L,
      reasoningOutputTokens = 0L,
      cost = None,
      apiCalls = None,
      wireTotal = None
    )
    assertEquals(u.freshInputTokens, 100L)

  /** A backend whose cache axes over-report past its own input total must cost
    * zero fresh input, not a negative charge eating the rest of the estimate.
    */
  test("inclusiveInput clamps fresh input at zero"):
    val u = Usage.inclusiveInput(
      totalInputTokens = 500_000L,
      cacheReadInputTokens = 300_000L,
      cacheWriteInputTokens = 250_000L,
      outputTokens = 0L,
      reasoningOutputTokens = 0L,
      cost = None,
      apiCalls = None,
      wireTotal = None
    )
    assertEquals(u.freshInputTokens, 0L)
    assertEquals(u.inputTokens, 550_000L)
