package orca

import orca.agents.Model
import orca.events.{Cost, ModelPricing, Pricing, PricingTable}
import orca.testkit.Usages.usage

class PricingTest extends munit.FunSuite:

  // $1/M input and $5/M output, so 1M input + 500k output estimates at $3.50.
  private val table: PricingTable = Map(
    Model("opus") -> ModelPricing(
      inputUsdPerMillion = 1,
      cacheReadUsdPerMillion = BigDecimal("0.10"),
      outputUsdPerMillion = 5,
      cacheWriteUsdPerMillion = 2
    )
  )

  private val model: Option[Model] = Some(Model("opus"))

  test(
    "a reported zero on a call that spent tokens falls back to the estimate"
  ):
    assertEquals(
      Pricing.resolve(
        table,
        model,
        usage(input = 1_000_000L, output = 500_000L, cost = Some(BigDecimal(0)))
      ),
      Some(Cost(BigDecimal("3.5"), estimated = true))
    )

  test("a reported zero on a call with no pricing row resolves to nothing"):
    assertEquals(
      Pricing.resolve(
        table,
        Some(Model("unlisted")),
        usage(input = 1_000_000L, output = 500_000L, cost = Some(BigDecimal(0)))
      ),
      None
    )

  test("a reported zero on a call that spent nothing is kept as reported"):
    assertEquals(
      Pricing.resolve(
        table,
        model,
        usage(input = 0L, output = 0L, cost = Some(BigDecimal(0)))
      ),
      Some(Cost(BigDecimal(0), estimated = false))
    )

  test("a non-zero reported cost wins over the price table"):
    assertEquals(
      Pricing.resolve(
        table,
        model,
        usage(
          input = 1_000_000L,
          output = 500_000L,
          cost = Some(BigDecimal("0.42"))
        )
      ),
      Some(Cost(BigDecimal("0.42"), estimated = false))
    )

  test("the shipped table prices claude-mythos-5"):
    // Invitation-only, so no backend default pins it and
    // `DefaultModelsPricedTest` can't reach the row; without this a mythos turn
    // would show tokens against no dollars.
    assertEquals(
      Pricing.resolve(
        Pricing.default.table,
        Some(Model("claude-mythos-5")),
        usage(input = 1_000_000L, output = 0L)
      ),
      Some(Cost(BigDecimal("10"), estimated = true))
    )
