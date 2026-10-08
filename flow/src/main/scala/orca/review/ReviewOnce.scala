package orca.review

import orca.{FlowContext, InStage, Par}
import orca.agents.{JsonData, given}
import orca.plan.Title

// Not under capture checking: `derives JsonData` expands tapir's Schema macro,
// which does not type-check there (same split as FixRequest.scala).

/** One reviewer's findings, named so a report can attribute each one. */
case class ReviewerFindings(reviewer: String, findings: List[ReviewFinding])
    derives JsonData

/** What [[reviewOnce]] found, in reviewer-completion order. */
case class ReviewReport(
    target: ReviewTarget,
    byReviewer: List[ReviewerFindings]
) derives JsonData:

  /** The report as markdown, fit to print or post on a PR. */
  def render: String =
    val attributed = byReviewer.flatMap(r => r.findings.map(r.reviewer -> _))
    val header =
      s"## Review: ${target.summary}\n\n" +
        s"${attributed.size} finding(s) from ${byReviewer.size} reviewer(s) " +
        s"across ${target.changedFiles.size} changed file(s)."
    if attributed.isEmpty then s"$header\n\nNo findings reported."
    else s"$header\n\n${attributed.map(renderFinding).mkString("\n")}"

  private def renderFinding(reviewer: String, finding: ReviewFinding): String =
    val where = finding.location.fold("")(l => s" — `${l.text}`")
    val suggestion = finding.suggestion.fold("")(s => s"\n  - suggestion: $s")
    s"- **${finding.title}** ($reviewer)$where\n" +
      s"  - ${finding.description}$suggestion"

/** Review `target` once, without fixing anything: `selection` picks from
  * `reviewers` (by default a cheap picker, after each reviewer's `files:`
  * filter), and the picked reviewers run concurrently.
  */
def reviewOnce(
    reviewers: List[ReviewerAgent[?]],
    target: ReviewTarget,
    selection: ReviewerSelector = ReviewerSelector.agentDriven
)(using FlowContext, InStage): ReviewReport =
  val pick = selection.prepare(
    RosterEntry.roster(reviewers),
    Title(target.summary),
    target.changedFiles
  )
  val picked = pick(Nil) // one round, so no earlier review batches
  val prompt = reviewOncePrompt(target)
  ReviewReport(
    target,
    Par.mapUnordered(MaxConcurrentReviewTasks)(picked): entry =>
      ReviewerFindings(
        entry.name.value,
        entry.agent
          .withRole(ReviewerPrompts.Role)
          .resultAs[ReviewResult]
          .autonomous
          .run(prompt)
          .findings
      )
  )

private def reviewOncePrompt(target: ReviewTarget): String =
  s"Under review: ${target.summary}\n\n" +
    s"The complete diff is in `${target.diffPath}` — read it first.\n\n" +
    ReviewLoopPrompts.ReviewOnce
