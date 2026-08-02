package orca.runner.manifest

import orca.events.{Cost, Usage}

/** Tokens and resolved cost for one bucket of LLM calls. */
private[manifest] case class Tally(usage: Usage, cost: Option[Cost]):
  def +(that: Tally): Tally =
    Tally(usage + that.usage, (cost ++ that.cost).reduceOption(_ + _))

private[manifest] object Tally:
  val empty: Tally = Tally(Usage.empty, None)

/** A run's token and cost accounting, folded from `TokensUsed` one turn at a
  * time.
  *
  * Every turn is retained anyway (the manifest persists the per-turn log), so
  * the breakdowns are grouped on demand rather than maintained as parallel maps
  * — which is what makes each axis sum back to the total by construction
  * instead of by convention.
  */
private[manifest] case class CostAccumulator(
    entries: Vector[(ManifestTurn, Tally)] = Vector.empty
):
  /** `cost` is the already-resolved figure for this call — see
    * [[orca.events.Pricing.resolve]].
    */
  def record(
      turn: ManifestTurn,
      usage: Usage,
      cost: Option[Cost]
  ): CostAccumulator =
    copy(entries = entries :+ (turn, Tally(usage, cost)))

  def turns: List[ManifestTurn] = entries.map((turn, _) => turn).toList

  def summarise: ManifestCostSummary =
    val total = entries.foldLeft(Tally.empty)((acc, e) => acc + e._2)
    ManifestCostSummary(
      total = ManifestUsage.of(total.usage),
      cost = total.cost,
      byRole = subtotals(_.role),
      byAgent = subtotals(turn => Some(turn.agent)),
      byStage = subtotals(_.stage)
    )

  /** Sorted by key so a manifest rewritten mid-run doesn't reorder its
    * breakdowns between writes.
    */
  private def subtotals(
      keyOf: ManifestTurn => Option[String]
  ): List[ManifestSubtotal] =
    entries
      .groupMapReduce((turn, _) => keyOf(turn))((_, tally) => tally)(_ + _)
      .toList
      .map: (key, tally) =>
        ManifestSubtotal(key, ManifestUsage.of(tally.usage), tally.cost)
      .sortBy(_.key)
