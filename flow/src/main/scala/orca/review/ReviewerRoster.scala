package orca.review

import orca.agents.Agent

import scala.util.matching.Regex

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
final class RosterEntry private[review] (
    private val reviewer: ReviewerAgent[?],
    private[review] val id: ReviewerId
):
  /** The reviewer's bare slug — its identity, and what the picker LLM is shown
    * and asked to echo. The cost-attribution role tag
    * ([[ReviewerPrompts.Role]]) is applied only later, at the loop's emission
    * edge.
    */
  def name: String = reviewer.definition.name

  /** The reviewer's purpose blurb, from its definition. */
  def description: String = reviewer.definition.description

  /** The reviewer's `files:` filter, from its definition. */
  def filePattern: Option[Regex] = reviewer.definition.filePattern

  /** Whether the reviewer applies to `changedFiles` — see
    * [[Reviewer.appliesTo]].
    */
  def appliesTo(changedFiles: List[String]): Boolean =
    reviewer.definition.appliesTo(changedFiles)

  private[review] def agent: Agent[?] = reviewer.agent

private[review] object RosterEntry:
  /** The whole roster, each reviewer wrapped once. The only place a
    * [[ReviewerId]] is minted.
    */
  def roster(reviewers: List[ReviewerAgent[?]]): List[RosterEntry] =
    reviewers.zipWithIndex.map((r, i) => new RosterEntry(r, ReviewerId(i)))

/** One round of reviews, with each reviewer's individual outcome preserved and
  * kept in configured order, so the loop can decide which reviewers to re-run
  * next iteration based on which ones reported findings.
  */
case class ReviewBatch(outcomes: List[(RosterEntry, ReviewResult)]):
  def reviewersWithFindings: List[RosterEntry] =
    outcomes.collect { case (r, rr) if rr.findings.nonEmpty => r }
