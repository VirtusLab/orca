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

  /** The loop used up its `max` fix turns with the finding still reported.
    */
  case CapReached(max: Int)

  /** The lint gate still reports it after the fix turn scoped to it. */
  case LintStillFailing

  def describe: String = this match
    case Declined(text)   => text
    case NoFixes          => "fixer reported no fixes"
    case Unaccounted      => "fixer did not report on it"
    case CapReached(max)  => s"max fix turns ($max) reached"
    case LintStillFailing => "lint still failing after its fix turn"

/** A finding the run ends without resolving, the reason recorded for it, and
  * where it points.
  *
  * Entries merge across rounds by `id`, so a finding re-reported under another
  * title is still one entry, and two findings sharing a title stay two.
  * `location` is what the reviewer that reported it gave, carried here so an
  * exit rounds later still points at the code; `None` where the reviewer named
  * no place.
  */
case class OpenFinding(
    id: FindingId,
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

/** Why a review never ran, so nothing it would have found is in the record. */
enum SkippedReview derives JsonData:
  /** The run recorded no starting commit to diff the whole-run review against.
    */
  case NoStartingCommit

  def describe: String = this match
    case NoStartingCommit =>
      "the run has no usable starting commit to diff against"

/** The run's record of what its review left open, one entry per finding however
  * many rounds reported it, and whether the review was skipped — in which case
  * `findings` holds only what the loop was seeded with.
  */
case class OpenFindings(
    findings: List[OpenFinding],
    skipped: Option[SkippedReview]
) derives JsonData:
  /** Nothing to report: every finding resolved, and the review ran. */
  def isEmpty: Boolean = findings.isEmpty && skipped.isEmpty

object OpenFindings:
  val empty: OpenFindings = OpenFindings(Nil, skipped = None)

  /** Silent — the fix loop prints these itself when it exits. */
  given Announce[OpenFindings] = Announce.from(_ => "")
