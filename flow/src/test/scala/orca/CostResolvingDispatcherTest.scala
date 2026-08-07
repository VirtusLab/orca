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

  private val table = PriceList(
    Map(Model("opus") -> ModelPricing(1, BigDecimal("0.10"), 5, 2)),
    lastUpdated = LocalDate.of(2026, 1, 15)
  )

  private def recorder(
      into: AtomicReference[List[OrcaEvent]]
  ): OrcaListener = e => { val _ = into.updateAndGet(e :: _) }

  // The point of resolving before the fan-out: two listeners can no longer
  // report different dollars for one turn, because neither prices anything.
  test("every listener receives the same resolved cost for one turn"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(
      table,
      e => { recorder(seen).onEvent(e); recorder(seen).onEvent(e) }
    )
    dispatcher.onEvent(
      OrcaEvent.TokensUsed("claude", Some(Model("opus")), usage(1_000_000L, 0L))
    )
    assertEquals(
      seen.get().collect { case t: OrcaEvent.TokensUsed => t.cost },
      List(
        Some(Cost(BigDecimal("1.0"), estimated = true)),
        Some(Cost(BigDecimal("1.0"), estimated = true))
      )
    )

  test("an event that isn't a turn passes through untouched"):
    val seen = AtomicReference[List[OrcaEvent]](Nil)
    val dispatcher = new CostResolvingDispatcher(table, recorder(seen))
    dispatcher.onEvent(OrcaEvent.Step("hi"))
    assertEquals(seen.get(), List(OrcaEvent.Step("hi")))
