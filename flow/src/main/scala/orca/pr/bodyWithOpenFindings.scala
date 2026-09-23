package orca.pr

import orca.FlowContext
import orca.events.OrcaEvent
import orca.review.OpenFindings

/** `body`, then an "Open review findings" section naming what a review loop
  * left open, so a PR whose review did not come back clean says so: one bullet
  * per entry, its title and the reason it is still open, verbatim from the
  * loop, not reworded by any model, and where it points when the reviewer that
  * reported it named a place. `body` unchanged when nothing is open.
  *
  * The single home for the assembly — [[openPrFromBranch]], [[openPrIfGitHub]]
  * and a flow writing its own body (`gh.updatePr`) all go through it.
  */
def bodyWithOpenFindings(body: String, open: OpenFindings): String =
  openFindingsSection(open).fold(body)(section => s"$body\n\n$section")

/** Runs `step`, then reports `open` in the run output as one `Step` holding the
  * same section the PR body gets — also when `step` throws, so a run whose PR
  * failed still shows what its review left open. Nothing when nothing is open.
  *
  * [[openPrFromBranch]] and [[openPrIfGitHub]] already do this; a flow that
  * writes its own PR body wraps its final PR step in it.
  */
def reportingOpenFindings[T](open: OpenFindings)(step: => T)(using
    ctx: FlowContext
): T =
  try step
  finally openFindingsSection(open).foreach(s => ctx.emit(OrcaEvent.Step(s)))

/** The "Open review findings" section, or `None` when nothing is open. */
private[pr] def openFindingsSection(open: OpenFindings): Option[String] =
  Option.when(open.findings.nonEmpty):
    val bullets = open.findings.map: f =>
      val where = f.location.fold("")(l => s" (`${l.text}`)")
      val reason = f.reasonLine
      if reason.isEmpty then s"- ${f.titleLine}$where"
      else s"- ${f.titleLine}$where — $reason"
    // "Left open", not "findings unfixed": the record also holds what the fixer
    // refused as not a problem, and the one entry a skipped review leaves,
    // alongside what stayed open at the cap.
    val lead = "What the run's review left open, each with the reason " +
      "recorded — including findings the fixer declined, and a review the " +
      "run could not run:"
    (List("## Open review findings", "", lead, "") ++ bullets).mkString("\n")
