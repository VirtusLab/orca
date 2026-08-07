package orca.events

/** A USD cost with a flag that propagates through addition: any aggregate
  * mixing at least one estimated input is itself flagged as an estimate.
  *
  * Lives here rather than beside `Pricing` (which resolves it) so
  * [[OrcaEvent.TokensUsed]] can carry one — `tools` holds no price table and
  * doesn't depend on the flow module.
  */
case class Cost(amount: BigDecimal, estimated: Boolean):
  def +(that: Cost): Cost =
    Cost(amount + that.amount, estimated || that.estimated)
