package orca

import orca.agents.Model
import orca.events.{Cost, CostBasis, ModelPricing, Pricing, PricingTable}
import orca.testkit.Usages.usage

import java.time.LocalDate

class PricingTest extends munit.FunSuite:

  private val ratesAsOf: LocalDate = LocalDate.of(2026, 1, 15)

  // $1/M input and $5/M output, so 1M input + 500k output estimates at $3.50.
  private val table: PricingTable = Map(
    Model("opus") -> ModelPricing(
      inputUsdPerMillion = 1,
      cacheReadUsdPerMillion = BigDecimal("0.10"),
      outputUsdPerMillion = 5,
      cacheWriteUsdPerMillion = 2,
      ratesAsOf = ratesAsOf
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
      Some(Cost(BigDecimal("3.5"), CostBasis.Estimated(ratesAsOf)))
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
      Some(Cost(BigDecimal(0), CostBasis.Reported))
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
      Some(Cost(BigDecimal("0.42"), CostBasis.Reported))
    )

  test("the shipped table prices claude-mythos-5"):
    assertEquals(
      Pricing
        .resolve(
          Pricing.default,
          Some(Model("claude-mythos-5")),
          usage(input = 1_000_000L, output = 0L)
        )
        .map(_.amount),
      Some(BigDecimal("10"))
    )
