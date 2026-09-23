package orca.pr

import orca.FlowContext
import orca.events.OrcaEvent
import orca.review.OpenFindings

/** `body`, then an "Open review findings" section naming what a review loop
  * left open, so a PR whose review did not come back clean says so: whether the
  * review was skipped, then one bullet per entry, its title and the reason it
  * is still open, verbatim from the loop, not reworded by any model, and where
  * it points when the reviewer that reported it named a place. `body` unchanged
  * when nothing is open.
  *
  * The single home for the assembly — [[openPrFromBranch]], [[openPrIfGitHub]]
  * and a flow writing its own body (`gh.updatePr`) all go through it.
  */
def bodyWithOpenFindings(body: String, open: OpenFindings): String =
  openFindingsSection(open).fold(body)(section => s"$body\n\n$section")

/** Prints `open` to the run output as one `Step` holding the same section the
  * PR body gets; nothing when nothing is open.
  *
  * [[openPrFromBranch]] and [[openPrIfGitHub]] call it before their PR step, so
  * a failed PR still leaves the findings in the output. A flow that writes its
  * own PR body calls it the same way.
  */
def reportOpenFindings(open: OpenFindings)(using ctx: FlowContext): Unit =
  openFindingsSection(open).foreach(s => ctx.emit(OrcaEvent.Step(s)))

/** The "Open review findings" section, or `None` when nothing is open. */
private[pr] def openFindingsSection(open: OpenFindings): Option[String] =
  Option.when(!open.isEmpty):
    val skipped =
      open.skipped.map(s => s"The review did not run: ${s.describe}.")
    val bullets = open.findings.map: f =>
      val where = f.location.fold("")(l => s" (`${l.text}`)")
      val reason = f.reasonLine
      if reason.isEmpty then s"- ${f.titleLine}$where"
      else s"- ${f.titleLine}$where — $reason"
    // "Left open", not "findings unfixed": the record also holds what the fixer
    // refused as not a problem, alongside what stayed open at the cap.
    val listed = Option.when(bullets.nonEmpty):
      "What the run's review left open, each with the reason recorded — " +
        s"including findings the fixer declined:\n\n${bullets.mkString("\n")}"
    (Some("## Open review findings") :: skipped :: listed :: Nil).flatten
      .mkString("\n\n")
