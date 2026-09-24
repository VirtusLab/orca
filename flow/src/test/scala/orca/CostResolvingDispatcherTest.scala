package orca

import orca.agents.Model
import orca.events.{
  Cost,
  CostBasis,
  CostResolvingDispatcher,
  ModelPricing,
  OrcaEvent,
  OrcaListener,
  PricingTable
}
import orca.testkit.Usages.usage

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

class CostResolvingDispatcherTest extends munit.FunSuite:

  private val ratesAsOf: LocalDate = LocalDate.of(2026, 1, 15)

  private val prices: PricingTable = Map(
    Model("opus") -> ModelPricing(
      inputUsdPerMillion = 1,
      cacheReadUsdPerMillion = BigDecimal("0.10"),
      outputUsdPerMillion = 5,
      cacheWriteUsdPerMillion = 2,
      ratesAsOf = ratesAsOf
    )
  )

  private def recorder(
      into: AtomicReference[List[OrcaEvent]]
  ): OrcaListener = e => { val _ = into.updateAndGet(e :: _) }

  private def spent(model: String): OrcaEvent.UnpricedTurn =
    OrcaEvent.UnpricedTurn(
      "claude",
      Some(Model(model)),
      usage(1_000_000L, 0L),
      role = Some("reviewer"),
      turn = 2,
      session = Some("s1")
    )

  // Resolving before the fan-out is what stops two listeners reporting
  // different dollars for one turn: the figure is settled by the time any of
  // them sees the event, and none of them prices anything.
  test("the fan-out receives only the turn with its cost resolved"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(spent("opus"))
    assertEquals(
      seen.get(),
      List(
        OrcaEvent.TokensUsed(
          spent("opus"),
          Some(Cost(BigDecimal("1.0"), CostBasis.Estimated(ratesAsOf)))
        )
      )
    )

  test("a turn whose model misses the table passes through unpriced"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(spent("unlisted"))
    assertEquals(
      seen.get().collect { case OrcaEvent.TokensUsed(_, cost) => cost },
      List(None)
    )

  test("an event that isn't a turn passes through untouched"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(OrcaEvent.Step("hi"))
    assertEquals(seen.get(), List(OrcaEvent.Step("hi")))
