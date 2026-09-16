package orca.pr

import orca.review.IgnoredIssues

/** The PR-body section naming what a review loop left open, so a PR whose
  * review did not come back clean says so: one bullet per finding — its title
  * and the reason it is still open, verbatim from the loop, not reworded by any
  * model. Title and reason only: an [[IgnoredIssue]] carries no location, so
  * nothing here says where a finding points. `None` when nothing is open, so
  * the body carries no section.
  */
def renderOpenFindings(open: IgnoredIssues): Option[String] =
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
