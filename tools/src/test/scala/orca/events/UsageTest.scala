package orca.events

class UsageTest extends munit.FunSuite:

  test("+ adds every axis independently across usages from different backends"):
    // A run mixes backends reporting different axis subsets. Every axis must
    // add independently, whatever order the events arrive in.
    val withWrites = Usage(
      freshInputTokens = 100L,
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      outputTokens = 100L,
      reasoningOutputTokens = 0L,
      cost = Some(BigDecimal("0.10")),
      apiCalls = Some(2L)
    )
    val fewerWrites = Usage(
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
    val plain = Usage(
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
        cacheReadInputTokens = 800L,
        cacheWriteInputTokens = 390L,
        outputTokens = 153L,
        reasoningOutputTokens = 20L,
        cost = Some(BigDecimal("0.11")),
        apiCalls = Some(7L)
      )
    )
    assertEquals(fewerWrites + withWrites, withWrites + fewerWrites)

  test("empty is the identity of +"):
    val u = Usage(
      freshInputTokens = 10L,
      cacheReadInputTokens = 4L,
      cacheWriteInputTokens = 3L,
      outputTokens = 5L,
      reasoningOutputTokens = 1L,
      cost = Some(BigDecimal("0.02")),
      apiCalls = Some(2L)
    )
    assertEquals(Usage.empty + u, u)
    assertEquals(u + Usage.empty, u)

  test("inputTokens totals the three disjoint input axes"):
    val u = Usage(
      freshInputTokens = 10L,
      cacheReadInputTokens = 4L,
      cacheWriteInputTokens = 3L,
      outputTokens = 0L,
      reasoningOutputTokens = 0L,
      cost = None,
      apiCalls = None
    )
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

  test("inclusiveInput splits the same way when the wire total agrees"):
    val u = Usage.inclusiveInput(
      totalInputTokens = 1_000L,
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      outputTokens = 200L,
      reasoningOutputTokens = 0L,
      cost = None,
      apiCalls = None,
      wireTotal = Some(1_200L)
    )
    assertEquals(u.freshInputTokens, 100L)
    assertEquals(u.inputTokens + u.outputTokens, 1_200L)

  /** A wire total above the decoded axes means the backend carries a counter
    * this decoder drops. The residue is logged, never folded into the axes —
    * inventing tokens would bill for a category nobody can name.
    */
  test("inclusiveInput leaves the axes alone when the wire total is larger"):
    val u = Usage.inclusiveInput(
      totalInputTokens = 1_000L,
      cacheReadInputTokens = 600L,
      cacheWriteInputTokens = 300L,
      outputTokens = 200L,
      reasoningOutputTokens = 0L,
      cost = None,
      apiCalls = None,
      wireTotal = Some(1_500L)
    )
    assertEquals(u.freshInputTokens, 100L)
    assertEquals(u.inputTokens + u.outputTokens, 1_200L)
