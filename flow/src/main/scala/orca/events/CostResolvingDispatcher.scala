package orca.events

/** Prices each [[OrcaEvent.UnpricedTurn]] once, on the way into the listener
  * fan-out, and hands every listener the resulting [[OrcaEvent.TokensUsed]]
  * instead.
  *
  * Wrapping the fan-out is what makes "the terminal summary, the on-disk cost
  * log and a user's own listener show different dollars" unrepresentable: no
  * listener holds a [[PricingTable]], so there is nothing left to configure
  * inconsistently.
  */
class CostResolvingDispatcher(pricing: PricingTable, inner: OrcaListener)
    extends OrcaListener:
  def onEvent(event: OrcaEvent): Unit = event match
    case t: OrcaEvent.UnpricedTurn =>
      inner.onEvent(
        OrcaEvent.TokensUsed(t, Pricing.resolve(pricing, t.model, t.usage))
      )
    case other => inner.onEvent(other)
