package orca.pr

import orca.review.IgnoredIssues

/** `body`, then an "Open review findings" section naming what a review loop
  * left open, so a PR whose review did not come back clean says so: one bullet
  * per entry, its title and the reason it is still open, verbatim from the
  * loop, not reworded by any model. Title and reason only: an
  * [[orca.review.IgnoredIssue]] carries no location, so nothing here says where
  * a finding points. `body` unchanged when nothing is open.
  *
  * The single home for the assembly — [[openPrFromBranch]], [[openPrIfGitHub]]
  * and a flow writing its own body (`gh.updatePr`) all go through it.
  */
def bodyWithOpenFindings(body: String, open: IgnoredIssues): String =
  if open.issues.isEmpty then body
  else
    val bullets = open.issues.map: i =>
      val reason = i.reasonLine
      if reason.isEmpty then s"- ${i.titleLine}"
      else s"- ${i.titleLine} — $reason"
    // "Left open", not "findings unfixed": the loop's record holds what the
    // fixer declined as not an issue, and the one entry a skipped review
    // leaves, alongside what stayed open at the cap.
    val lead = "What the run's review left open, each with the reason " +
      "recorded — including findings the fixer declined, and a review the " +
      "run could not run:"
    val section =
      (List("## Open review findings", "", lead, "") ++ bullets).mkString("\n")
    s"$body\n\n$section"
