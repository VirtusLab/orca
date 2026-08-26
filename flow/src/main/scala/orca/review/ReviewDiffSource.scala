package orca.review

import orca.BoundedDiff
import orca.tools.GitTool

/** A change set as the loop hands it out: the diff text a reviewer is sent, and
  * the paths describing the same change set. Sampled together, so a consumer
  * can never pair one round's diff with another's file list.
  */
private[review] case class DiffSample(diff: String, paths: List[String])

/** Where `reviewAndFixLoop` gets the change set under review. */
enum ReviewDiff:
  /** Everything the enclosing stage has produced since it began (ADR 0018
    * §2.1), re-sampled every round so each round's reviewers see the fixer's
    * edits whether or not it committed them.
    */
  case SampleFromStage

  /** Everything the run has produced since it started — since the commit HEAD
    * pointed at when the run bound its branch, recorded in the progress log —
    * re-sampled every round like [[SampleFromStage]]. For a review over the
    * whole branch, where a stage-scoped change set would miss what earlier
    * stages committed.
    *
    * A run whose progress log records no usable commit has no base to diff
    * against: `reviewAndFixLoop` then says so and returns without reviewing.
    */
  case WholeRun

  /** A caller-pinned diff, sent as given. Pinning also changes what reviewers
    * are told: the prompt does not claim the change set covers the stage, no
    * base commit is named (the pinned set need not be the stage's), the
    * selector's changed-file list is scraped from the diff text, and every
    * later round finds the same text, so a resumed reviewer is told there is no
    * new change set.
    */
  case Pinned(diff: String)

/** How the loop reads a [[ReviewDiff]] each round. Every answer that depends on
  * where the diff came from lives here, so no consumer re-decides from the
  * enum.
  */
private[review] sealed trait ReviewDiffSource:
  /** This round's change set. Re-sampled each round, so every reviewer that
    * round sees the fixer's later edits.
    */
  def sample(): DiffSample

  /** The commit reviewers are told the diff was sampled against, so a
    * shell-capable reviewer can read past it.
    */
  def base: Option[String]

  /** The sentence introducing the diff in the initial-review prompt; it states
    * what the change set covers.
    */
  def diffIntro: String

  /** The changed files [[ReviewerSelector]] is handed at loop start. */
  def selectorFiles: List[String]

private[review] object ReviewDiffSource:
  /** Reads [[ReviewDiff.SampleFromStage]] (ADR 0018 §2.1): everything the
    * working tree has changed since the enclosing stage began.
    */
  def stage(git: GitTool, base: Option[String]): ReviewDiffSource =
    Sampled(git, base, StageDiffIntro)

  /** Reads [[ReviewDiff.WholeRun]]: everything the working tree has changed
    * since `start`, the commit the run bound at.
    */
  def wholeRun(git: GitTool, start: String): ReviewDiffSource =
    Sampled(git, Some(start), WholeRunDiffIntro)

  /** Everything the working tree has changed since `base`, bounded to
    * [[BoundedDiff.ReviewThreshold]]. Private, and built only by [[stage]] and
    * [[wholeRun]]: how far back `base` reaches and what `diffIntro` tells the
    * reviewer it covers are one decision, and a caller free to pair them itself
    * could hand over a whole branch described as one stage's work.
    */
  private case class Sampled(
      git: GitTool,
      base: Option[String],
      diffIntro: String
  ) extends ReviewDiffSource:
    def sample(): DiffSample =
      val changes = git.reviewChanges(base)
      DiffSample(
        BoundedDiff.reviewPayload(changes.diff, changes.files),
        changes.files.map(_.path)
      )

    def selectorFiles: List[String] = git.changedFiles(base)

  private val StageDiffIntro: String =
    "Diff (everything this task has changed since its stage began, " +
      "committed or not). Do not use `git diff HEAD` instead — it does not " +
      "show work that has been committed:"

  // Says the change set reaches back past the enclosing stage, so a reviewer
  // does not read it as this stage's work.
  private val WholeRunDiffIntro: String =
    "Diff (everything this run has changed since it started, across every " +
      "stage, committed or not). Do not use `git diff HEAD` instead — it does " +
      "not show work that has been committed:"

  /** Reads [[ReviewDiff.Pinned]]: the caller has already decided what a
    * reviewer should see, so the text is sent as given and only that text can
    * name its files.
    */
  case class Pinned(diff: String) extends ReviewDiffSource:
    // The diff is constant, so its file list is scraped once rather than per
    // round.
    private val pinnedSample: DiffSample =
      DiffSample(diff, extractChangedFiles(diff))

    def sample(): DiffSample = pinnedSample
    def base: Option[String] = None
    def selectorFiles: List[String] = pinnedSample.paths

    // Says nothing about how far back the change set reaches: a pinned diff
    // need not be stage-base-to-working-tree.
    def diffIntro: String = "Diff (the change set under review):"

  /** Parse a unified diff and return the changed file paths (the `b/` side of
    * each `+++ b/<path>` header). Filters out `/dev/null` so deletions don't
    * pollute the list. Order matches first appearance in the diff.
    *
    * Only for [[Pinned]], where the diff text is all there is. Diff text can't
    * name every changed file: a binary change and a 100%-similarity rename
    * carry no `+++` header, and for a path with a space the capture includes
    * git's disambiguating trailing tab. [[Sampled]] asks git instead.
    */
  private[review] def extractChangedFiles(diff: String): List[String] =
    "(?m)^\\+\\+\\+ b/(.+)$".r
      .findAllMatchIn(diff)
      .map(_.group(1))
      .filterNot(_ == "/dev/null")
      .toList
      .distinct
