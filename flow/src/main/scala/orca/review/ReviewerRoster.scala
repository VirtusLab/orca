package orca.review

import orca.agents.{Agent, BackendTag}

// The vocabulary `reviewAndFixLoop` and every [[ReviewerSelector]] speak about
// the configured reviewers. Kept out of `ReviewLoop.scala` so the loop file
// holds only the loop, and out of capture checking, which none of it needs.

/** One reviewer's place in the roster, minted positionally when the roster is
  * built. Keys everything the loop records per reviewer, so two reviewers
  * wrapping the same base agent — which [[buildReviewers]] produces — stay
  * distinct.
  */
private[review] opaque type ReviewerId = Int

private[review] object ReviewerId:
  def apply(position: Int): ReviewerId = position

/** An opaque handle to one reviewer in `reviewAndFixLoop`'s configured roster.
  *
  * The `private[review]` constructor means a [[ReviewerSelector]] can only
  * return a subset/permutation of the entries it was handed — a foreign
  * reviewer is unrepresentable, so the loop needs no runtime roster-membership
  * defences.
  */
final class RosterEntry[B <: BackendTag] private[review] (
    private[review] val agent: Agent[B],
    private[review] val id: ReviewerId
):
  /** The reviewer's bare slug — its identity, and what the picker LLM is shown
    * and asked to echo. The `reviewer` cost-attribution role tag is applied
    * only later, at the loop's emission edge.
    */
  def name: String = agent.name

private[review] object RosterEntry:
  /** Wrap a roster agent, binding its backend tag into the entry's `B`. */
  def wrap(a: Agent[?], id: ReviewerId): RosterEntry[?] =
    a match
      case a: Agent[b] => new RosterEntry[b](a, id)

  /** The whole roster, each agent wrapped once. The only place a [[ReviewerId]]
    * is minted.
    */
  def roster(agents: List[Agent[?]]): List[RosterEntry[?]] =
    agents.zipWithIndex.map((a, i) => wrap(a, ReviewerId(i)))

/** One round of reviews, with each reviewer's individual outcome preserved and
  * kept in configured order, so the loop can decide which reviewers to re-run
  * next iteration based on which ones found issues.
  */
case class ReviewBatch(outcomes: List[(RosterEntry[?], ReviewResult)]):
  def reviewersWithIssues: List[RosterEntry[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
