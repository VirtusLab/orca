package orca.review

import orca.agents.{Announce, JsonData, given}
import orca.plan.Title
import orca.util.TextUtil

/** Why a finding is still open when the run ends — one case per way the loops
  * leave one behind. [[describe]] is the sentence a reader of the PR body or of
  * the exit block sees; nothing but [[OpenReason.Declined]] takes its words
  * from a model, so a run summary can count the rest by case.
  */
enum OpenReason derives JsonData:
  /** The fixer considered the finding and refused it, in `text`, its own words.
    */
  case Declined(text: String)

  /** The fixer neither fixed nor declined it, on a turn where it claimed no fix
    * at all — so the loop halted rather than reviewing again.
    */
  case NoFixes

  /** The fixer neither fixed nor declined it, on a turn that did fix something.
    * Only a single pass records this: a loop re-evaluates instead, and the
    * finding either comes back or is gone.
    */
  case Unaccounted

  /** The loop used up its `max` fix attempts with the finding still reported.
    */
  case CapReached(max: Int)

  /** The lint gate still reports it after the fix turn scoped to it. */
  case LintStillFailing

  /** The whole review never ran. The entry carrying this stands for the review,
    * not for anything a reviewer reported.
    */
  case ReviewSkipped

  def describe: String = this match
    case Declined(text)   => text
    case NoFixes          => "fixer reported no fixes"
    case Unaccounted      => "fixer did not report on it"
    case CapReached(max)  => s"max iterations ($max) reached"
    case LintStillFailing => "lint still failing after its fix turn"
    case ReviewSkipped =>
      "skipped: no usable starting commit for the diff base"

/** A finding the run ends without resolving, the reason recorded for it, and
  * where it points.
  *
  * Entries merge across rounds by title, so the title alone identifies the
  * finding. `location` is what the reviewer that reported it gave, carried here
  * so an exit rounds later still points at the code; `None` where nothing
  * placed it in the diff, as for a review that was skipped.
  */
case class OpenFinding(
    title: Title,
    reason: OpenReason,
    location: Option[Location]
) derives JsonData:
  /** A reviewer writes the title, so it can arrive with its own line breaks;
    * this is the form for a bullet that must not split.
    */
  def titleLine: String = oneLine(title.value)

  /** [[titleLine]] for the reason's prose, which a declined entry takes from a
    * model.
    */
  def reasonLine: String = oneLine(reason.describe)

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
