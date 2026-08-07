package orca.review

import orca.plan.Title

/** Every finding the confidence gate held back, per agent — what
  * `reviewAndFixLoop` reports as ignored at each of its exits.
  *
  * [[record]] is a monotone union: an agent that runs again refreshes and
  * extends what it reported, and can never erase it. Silence from an agent
  * therefore cannot delete a finding the fixer was never shown — the remaining
  * bias is the safe one, a reject whose issue later disappeared still being
  * reported. Findings are deduplicated per agent by title, the latest report
  * winning, so one finding re-reported every round yields one entry. The one
  * way out is [[remove]], driven by a fix verdict the loop observed.
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
    new GateLedger(
      entries.updated(
        owner,
        GateLedger.mergeLatestByTitle(entries.getOrElse(owner, Nil), dropped)(
          _.title
        )
      )
    )

  /** Every owner's entries with the given titles dropped. */
  def remove(titles: Set[Title]): GateLedger =
    new GateLedger(
      entries.view
        .mapValues(_.filterNot(i => titles.contains(i.title)))
        .toMap
    )

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

  /** `held` with `incoming` merged in, keyed by `title`: a title `held` already
    * carries is refreshed with the latest report in place, a new one is
    * appended, and duplicate titles within `incoming` collapse to the last. The
    * review loop's one notion of "the same finding again": a title names one
    * entry in the ledger, in the fixer's accumulated declines, and in the
    * [[IgnoredIssues]] any exit returns.
    */
  def mergeLatestByTitle[A](held: List[A], incoming: List[A])(
      title: A => Title
  ): List[A] =
    val latest = incoming.map(a => title(a) -> a).toMap
    val heldTitles = held.map(title).toSet
    val refreshed = held.map(a => latest.getOrElse(title(a), a))
    val added =
      incoming.map(title).distinct.filterNot(heldTitles.contains).map(latest)
    refreshed ++ added
