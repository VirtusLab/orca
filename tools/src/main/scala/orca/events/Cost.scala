package orca.events

import java.time.LocalDate

/** A USD cost and where its figure came from.
  *
  * Lives here rather than beside `Pricing` (which resolves it) so
  * [[OrcaEvent.TokensUsed]] can carry one — `tools` holds no price table and
  * doesn't depend on the flow module.
  */
case class Cost(amount: BigDecimal, basis: CostBasis):
  def +(that: Cost): Cost = Cost(amount + that.amount, basis + that.basis)

enum CostBasis:
  /** The backend reported the figure. */
  case Reported

  /** Estimated from pricing-table rates last checked on `ratesAsOf`. */
  case Estimated(ratesAsOf: LocalDate)

  /** An aggregate mixing at least one estimate is an estimate, dated by the
    * oldest rates that went into it.
    */
  def +(that: CostBasis): CostBasis = (this, that) match
    case (Estimated(a), Estimated(b)) =>
      Estimated(if a.isBefore(b) then a else b)
    case (Reported, b) => b
    case (a, Reported) => a
