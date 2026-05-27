package orca.review

import orca.llm.{Announce, JsonData, given}

case class ReviewResult(
    issues: List[ReviewIssue]
) derives JsonData

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil)

  /** Silent — `reviewAndFixLoop` emits per-reviewer Steps with the reviewer's
    * name; this would compete.
    */
  given Announce[ReviewResult] = Announce.from(_ => "")
