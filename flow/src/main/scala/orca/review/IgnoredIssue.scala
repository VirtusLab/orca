package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title
import orca.util.TextUtil

case class IgnoredIssue(title: Title, reason: String) derives JsonData:
  /** `title` on one line, for a bullet that must not split: a reviewer writes
    * it, so it can arrive with its own line breaks.
    */
  def titleLine: String = oneLine(title.value)

  /** `reason` on one line, as [[titleLine]]; the fixer writes it. Empty when
    * the reason is blank.
    */
  def reasonLine: String = oneLine(reason)

private def oneLine(text: String): String =
  TextUtil.collapseWhitespace(text.trim)

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  /** One bullet per issue, title and reason each on one line. */
  def format: String =
    issues.map(i => s"- ${i.titleLine}: ${i.reasonLine}").mkString("\n")

object IgnoredIssues:
  /** Silent — the fix loop prints these itself when it exits. */
  given Announce[IgnoredIssues] = Announce.from(_ => "")
