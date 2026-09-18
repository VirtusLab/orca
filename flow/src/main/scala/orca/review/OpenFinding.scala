package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title
import orca.util.TextUtil

/** A finding the run ends without resolving, and the reason recorded for it —
  * the fixer declined it, never reported on it, the round cap was hit, the lint
  * gate still fails, or the review could not run at all. The reason is the only
  * place that distinction survives, so it is written for a reader of the PR
  * body, not as a code.
  *
  * Only the title is carried, not the whole [[ReviewFinding]]: entries are
  * merged across rounds by title, and an entry seeded from an earlier review
  * has no location to carry.
  */
case class OpenFinding(title: Title, reason: String) derives JsonData:
  /** A reviewer writes the title, so it can arrive with its own line breaks;
    * this is the form for a bullet that must not split.
    */
  def titleLine: String = oneLine(title.value)

  /** [[titleLine]] for the reason. */
  def reasonLine: String = oneLine(reason)

private def oneLine(text: String): String =
  TextUtil.collapseWhitespace(text.trim)

/** The run's record of what its review left open, merged by title so one
  * finding is one entry however many rounds reported it.
  */
case class OpenFindings(findings: List[OpenFinding]) derives JsonData:
  /** One bullet per finding, title and reason each on one line. */
  def format: String =
    findings.map(f => s"- ${f.titleLine}: ${f.reasonLine}").mkString("\n")

object OpenFindings:
  /** Silent — the fix loop prints these itself when it exits. */
  given Announce[OpenFindings] = Announce.from(_ => "")
