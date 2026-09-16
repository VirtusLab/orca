package orca.pr

import orca.review.IgnoredIssues

/** A PR body made of the flow's own text and what its review loop left open:
  * the [[renderOpenFindings]] section follows the text, blank line between,
  * and is absent when nothing is open. The single home for the assembly —
  * [[openPrFromBranch]], [[openPrIfGitHub]] and a flow writing its own body
  * (`gh.updatePr`) all go through it, so every orca-opened PR words the
  * section the same way.
  */
def bodyWithOpenFindings(body: String, open: IgnoredIssues): String =
  (body :: renderOpenFindings(open).toList).mkString("\n\n")

/** The PR-body section naming what a review loop left open, so a PR whose
  * review did not come back clean says so: one bullet per finding — its title
  * and the reason it is still open, verbatim from the loop, not reworded by any
  * model. Title and reason only: an [[orca.review.IgnoredIssue]] carries no
  * location, so nothing here says where a finding points. `None` when nothing
  * is open, so the body carries no section.
  */
private[pr] def renderOpenFindings(open: IgnoredIssues): Option[String] =
  Option.when(open.issues.nonEmpty):
    val bullets = open.issues.map: i =>
      val reason = i.reasonLine
      if reason.isEmpty then s"- ${i.titleLine}"
      else s"- ${i.titleLine} — $reason"
    // "Left open", not "findings unfixed": the loop's record holds what the
    // fixer declined as not an issue alongside what stayed open at the cap,
    // and a skipped review's one entry is not a finding at all.
    val lead = "What the run's review left open, each with the reason " +
      "recorded — including findings the fixer declined:"
    (List("## Open review findings", "", lead, "") ++ bullets).mkString("\n")
