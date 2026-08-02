package orca.runner.manifest

import orca.events.{Cost, Usage}

/** Tokens and resolved cost for one bucket of LLM calls. `cost` is `Some` as
  * soon as any folded-in call had one, and [[Cost.+]] carries the estimated
  * flag along, so a bucket mixing reported and estimated calls reads as an
  * estimate.
  */
private[manifest] case class Tally(usage: Usage, cost: Option[Cost]):
  def +(that: Tally): Tally =
    Tally(usage + that.usage, (cost ++ that.cost).reduceOption(_ + _))

private[manifest] object Tally:
  val empty: Tally = Tally(Usage.empty, None)

/** A run's token and cost accounting, folded from `TokensUsed` one turn at a
  * time. Immutable, so [[RunManifestWriterState]] keeps it in its state record
  * like any other field; [[summarise]] renders the persisted shape and is
  * called only when a manifest is actually written.
  *
  * The three axes are the same calls grouped three ways, so each sums back to
  * the total — which is why the total is derived from one of them rather than
  * accumulated separately and left free to drift.
  */
private[manifest] case class CostAccumulator(
    byRole: Map[Option[String], Tally] = Map.empty,
    byAgent: Map[String, Tally] = Map.empty,
    byStage: Map[Option[String], Tally] = Map.empty,
    turns: Vector[ManifestTurn] = Vector.empty
):
  /** Folds one turn in, under the role/agent/stage keys the turn itself
    * carries. `cost` is the already-resolved figure for this call — see
    * [[orca.events.Pricing.resolve]].
    */
  def record(
      turn: ManifestTurn,
      usage: Usage,
      cost: Option[Cost]
  ): CostAccumulator =
    val tally = Tally(usage, cost)
    copy(
      byRole = add(byRole, turn.role, tally),
      byAgent = add(byAgent, turn.agent, tally),
      byStage = add(byStage, turn.stage, tally),
      turns = turns :+ turn
    )

  def summarise: ManifestCostSummary =
    val total = byAgent.values.foldLeft(Tally.empty)(_ + _)
    ManifestCostSummary(
      total = ManifestUsage.of(total.usage),
      cost = total.cost.map(ManifestCost.of),
      byRole = subtotals(byRole)(identity),
      byAgent = subtotals(byAgent)(Some(_)),
      byStage = subtotals(byStage)(identity)
    )

  private def add[K](
      map: Map[K, Tally],
      key: K,
      tally: Tally
  ): Map[K, Tally] =
    map.updated(key, map.get(key).fold(tally)(_ + tally))

  /** Sorted by key so a manifest rewritten mid-run doesn't reorder its
    * breakdowns between writes.
    */
  private def subtotals[K](
      map: Map[K, Tally]
  )(labelOf: K => Option[String]): List[ManifestSubtotal] =
    map.toList
      .map: (key, tally) =>
        ManifestSubtotal(
          key = labelOf(key),
          usage = ManifestUsage.of(tally.usage),
          cost = tally.cost.map(ManifestCost.of)
        )
      .sortBy(_.key)
