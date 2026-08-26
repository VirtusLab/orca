package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title

case class IgnoredIssue(title: Title, reason: String) derives JsonData

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  def format: String =
    issues.map(i => s"- ${i.title}: ${i.reason}").mkString("\n")

object IgnoredIssues:
  /** Silent — the fix loop prints these itself when it exits. */
  given Announce[IgnoredIssues] = Announce.from(_ => "")

/** `held` with `incoming` merged in, keyed by title: a title `held` already
  * carries is refreshed with the latest report in place, a new one is appended,
  * and duplicate titles within `incoming` collapse to the last. The review
  * loop's one notion of "the same finding again": a title names one entry in
  * the fixer's accumulated declines and in the [[IgnoredIssues]] any exit
  * returns.
  */
private[review] def mergeLatestByTitle(
    held: List[IgnoredIssue],
    incoming: List[IgnoredIssue]
): List[IgnoredIssue] =
  val latest = incoming.map(i => i.title -> i).toMap
  val heldTitles = held.map(_.title).toSet
  val refreshed = held.map(i => latest.getOrElse(i.title, i))
  val added =
    incoming.map(_.title).distinct.filterNot(heldTitles.contains).map(latest)
  refreshed ++ added
