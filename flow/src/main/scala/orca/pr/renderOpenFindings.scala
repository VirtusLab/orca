package orca.pr

import orca.review.IgnoredIssues

/** The PR-body section naming what a review loop left open, so a PR whose
  * review did not come back clean says so: one bullet per finding — its title
  * and the reason it is still open, verbatim from the loop, not reworded by any
  * model. `None` when nothing is open, so the body carries no section.
  */
def renderOpenFindings(open: IgnoredIssues): Option[String] =
  Option.when(open.issues.nonEmpty):
    val bullets = open.issues.map: i =>
      // The fixer writes the reason, so it can arrive with its own line breaks;
      // a bullet has to stay on one line.
      val reason = i.reason.trim.replaceAll("\\s+", " ")
      if reason.isEmpty then s"- ${i.title.value}"
      else s"- ${i.title.value} — $reason"
    val lead = "Findings the run's review reported and left unfixed, " +
      "each with the reason it is still open:"
    (List("## Open review findings", "", lead, "") ++ bullets).mkString("\n")
