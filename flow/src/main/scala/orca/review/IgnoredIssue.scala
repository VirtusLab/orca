package orca.review

import orca.llm.{Announce, JsonData, given}
import orca.plan.Title

case class IgnoredIssue(title: Title, reason: String) derives JsonData

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(
    issues ++ other.issues
  )
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String =
    issues.map(i => s"- ${i.title}: ${i.reason}").mkString("\n")

object IgnoredIssues:
  /** Silent — the fix loop already announces its outcome per iteration. */
  given Announce[IgnoredIssues] = Announce.from(_ => "")
