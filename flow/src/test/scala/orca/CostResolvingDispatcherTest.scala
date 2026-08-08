package orca

import orca.agents.Model
import orca.events.{
  Cost,
  CostResolvingDispatcher,
  ModelPricing,
  OrcaEvent,
  OrcaListener,
  PriceList
}
import orca.testkit.Usages.usage

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

class CostResolvingDispatcherTest extends munit.FunSuite:

  private val prices = PriceList(
    Map(
      Model("opus") -> ModelPricing(
        inputUsdPerMillion = 1,
        cacheReadUsdPerMillion = BigDecimal("0.10"),
        outputUsdPerMillion = 5,
        cacheWriteUsdPerMillion = 2
      )
    ),
    lastUpdated = LocalDate.of(2026, 1, 15)
  )

  private def recorder(
      into: AtomicReference[List[OrcaEvent]]
  ): OrcaListener = e => { val _ = into.updateAndGet(e :: _) }

  // Resolving before the fan-out is what stops two listeners reporting
  // different dollars for one turn: the figure is settled by the time any of
  // them sees the event, and none of them prices anything.
  test("the fan-out receives the turn with its cost already resolved"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(
      OrcaEvent.TokensUsed(
        "claude",
        Some(Model("opus")),
        usage(1_000_000L, 0L),
        cost = None
      )
    )
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.cost },
      List(Some(Cost(BigDecimal("1.0"), estimated = true)))
    )

  // The dispatcher is the single resolution point, so a cost that arrived from
  // somewhere else is replaced rather than trusted.
  test("a turn arriving with a cost already set is re-resolved"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(
      OrcaEvent.TokensUsed(
        "claude",
        Some(Model("opus")),
        usage(1_000_000L, 0L),
        cost = Some(Cost(BigDecimal("99.0"), estimated = false))
      )
    )
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.cost },
      List(Some(Cost(BigDecimal("1.0"), estimated = true)))
    )

  test("a turn whose model misses the table passes through unpriced"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(
      OrcaEvent.TokensUsed(
        "gemini",
        Some(Model("unlisted")),
        usage(1_000_000L, 0L),
        cost = None
      )
    )
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.cost },
      List(None)
    )

  test("an event that isn't a turn passes through untouched"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(prices, recorder(seen))
    dispatcher.onEvent(OrcaEvent.Step("hi"))
    assertEquals(seen.get(), List(OrcaEvent.Step("hi")))
