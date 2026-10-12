package orca.review

import orca.json.{JsonData, given}
import orca.plan.Task

// Not under capture checking: `derives JsonData` expands tapir's Schema macro,
// which does not type-check there (same split as FixRequest.scala).

/** One reviewer's findings, named so a report can attribute each one. */
case class ReviewerFindings(reviewer: String, findings: List[ReviewFinding])
    derives JsonData

/** What [[reviewOnce]] found over `changedFiles`, in reviewer-completion order.
  */
case class ReviewReport(
    task: Task,
    changedFiles: List[String],
    byReviewer: List[ReviewerFindings]
) derives JsonData:

  /** The report as markdown, fit to print or post on a PR. */
  def render: String =
    val attributed = byReviewer.flatMap(r => r.findings.map(r.reviewer -> _))
    val header =
      s"## Review: ${task.title.value}\n\n" +
        s"${attributed.size} finding(s) from ${byReviewer.size} reviewer(s) " +
        s"across ${changedFiles.size} changed file(s)."
    if attributed.isEmpty then s"$header\n\nNo findings reported."
    else s"$header\n\n${attributed.map(renderFinding).mkString("\n")}"

  private def renderFinding(reviewer: String, finding: ReviewFinding): String =
    val where = finding.location.fold("")(l => s" — `${l.text}`")
    val suggestion = finding.suggestion.fold("")(s => s"\n  - suggestion: $s")
    s"- **${finding.title}** ($reviewer)$where\n" +
      s"  - ${finding.description}$suggestion"
