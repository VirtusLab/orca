package orca.review

import orca.plan.Title

/** Every finding the confidence gate held back, per agent — what
  * `reviewAndFixLoop` reports as ignored at each of its exits.
  *
  * The only mutator is [[record]], a monotone union: an agent that runs again
  * refreshes and extends what it reported, and can never erase it. Silence from
  * an agent therefore cannot delete a finding the fixer was never shown — the
  * remaining bias is the safe one, a reject whose issue later disappeared still
  * being reported. Findings are deduplicated per agent by title, the latest
  * report winning, so one finding re-reported every round yields one entry.
  */
private[review] final class GateLedger private (
    entries: Map[GateLedger.Owner, List[ReviewIssue]]
):
  /** `owner`'s rejects with `dropped` merged in: a title it already holds is
    * refreshed in place, a new one is appended.
    */
  def record(
      owner: GateLedger.Owner,
      dropped: List[ReviewIssue]
  ): GateLedger =
    val latest: Map[Title, ReviewIssue] = dropped.map(i => i.title -> i).toMap
    val held = entries.getOrElse(owner, Nil)
    val refreshed = held.map(i => latest.getOrElse(i.title, i))
    val added = dropped
      .map(_.title)
      .distinct
      .filterNot(t => held.exists(_.title == t))
      .map(latest)
    new GateLedger(entries.updated(owner, refreshed ++ added))

  /** What `owner` had held back, in the order it first reported each finding.
    */
  def of(owner: GateLedger.Owner): List[ReviewIssue] =
    entries.getOrElse(owner, Nil)

private[review] object GateLedger:
  /** Whose rejects these are. The lint gate has no [[RosterEntry]], so it
    * cannot be addressed by a [[ReviewerId]].
    */
  enum Owner:
    case Reviewer(id: ReviewerId)
    case Lint

  val empty: GateLedger = new GateLedger(Map.empty)
