package orca.discovery

/** One key's winner: the value from the highest-precedence tier that defines
  * the key, plus the lower tiers that define it too, highest first.
  */
private[orca] case class TierWinner[T, V](
    key: String,
    tier: T,
    value: V,
    shadows: List[T]
)

/** Resolves per-key precedence between tiers of discovered files. */
private[orca] object TierPrecedence:

  /** One [[TierWinner]] per key across `byTier`, sorted by key. `byTier` lists
    * the tiers highest-precedence first, each with the values it defines; a
    * tier that defines nothing contributes no keys.
    */
  def resolve[T, V](byTier: List[(T, Map[String, V])]): List[TierWinner[T, V]] =
    byTier
      .flatMap(_._2.keySet)
      .distinct
      .sorted
      .map: key =>
        val hits = byTier.collect:
          case (tier, values) if values.contains(key) => tier -> values(key)
        val (winner, value) = hits.head
        TierWinner(key, winner, value, hits.tail.map(_._1))
