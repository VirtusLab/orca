package orca.events

/** Resolves each turn's cost once, on the way into the listener fan-out, and
  * hands every listener the same figure through [[OrcaEvent.TokensUsed.cost]].
  *
  * Wrapping the fan-out is what makes "the terminal summary, the on-disk cost
  * log and a user's own listener show different dollars" unrepresentable: no
  * listener holds a [[PriceList]], so there is nothing left to configure
  * inconsistently.
  */
class CostResolvingDispatcher(pricing: PriceList, inner: OrcaListener)
    extends OrcaListener:
  def onEvent(event: OrcaEvent): Unit = event match
    case turn: OrcaEvent.TokensUsed =>
      inner.onEvent(
        turn.copy(cost = Pricing.resolve(pricing.table, turn.model, turn.usage))
      )
    case other => inner.onEvent(other)
