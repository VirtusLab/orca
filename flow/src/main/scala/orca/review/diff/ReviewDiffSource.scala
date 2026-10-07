package orca.review.diff

import orca.BoundedDiff
import orca.gitref.CommitHash
import orca.tools.GitTool

/** How the loop reads an `orca.review.ReviewDiff` each round. Every answer that
  * depends on where the diff came from lives here, so no consumer re-decides
  * from the enum.
  */
private[review] sealed trait ReviewDiffSource:
  /** This round's change set. Re-sampled each round, so every reviewer that
    * round sees the fixer's later edits.
    */
  def sample(): DiffSample

  /** How far back [[sample]] reaches. */
  def coverage: DiffCoverage

private[review] object ReviewDiffSource:
  /** Reads `orca.review.ReviewDiff.SampleFromStage` (ADR 0018 §2.1): everything
    * the working tree has changed since the enclosing stage began.
    */
  def stage(git: GitTool, base: Option[CommitHash]): ReviewDiffSource =
    Sampled(git, DiffCoverage.Stage(base))

  /** Reads `orca.review.ReviewDiff.WholeRun`: everything the working tree has
    * changed since `start`, the commit the run bound at.
    */
  def wholeRun(git: GitTool, start: CommitHash): ReviewDiffSource =
    Sampled(git, DiffCoverage.Since(start))

  /** Reads `orca.review.ReviewDiff.Pinned`: the caller has already decided what
    * a reviewer should see, so the text is sent as given and only that text can
    * name its files.
    */
  def pinned(diff: String): ReviewDiffSource = Pinned(diff)

  /** Everything the working tree has changed since `coverage.base`, bounded to
    * [[BoundedDiff.ReviewThreshold]].
    */
  private case class Sampled(
      git: GitTool,
      coverage: DiffCoverage.Stage | DiffCoverage.Since
  ) extends ReviewDiffSource:
    def sample(): DiffSample =
      val changes = git.reviewChanges(coverage.base)
      DiffSample(
        BoundedDiff.reviewPayload(changes),
        changes.files.map(_.path),
        changes.sections
      )

  private case class Pinned(diff: String) extends ReviewDiffSource:
    // The diff is constant, so its file list is scraped once rather than per
    // round. No sections: a pinned sample is the same every round, so it never
    // reaches a cut that would need them.
    private val pinnedSample: DiffSample =
      DiffSample(diff, extractChangedFiles(diff), Map.empty)

    def sample(): DiffSample = pinnedSample
    def coverage: DiffCoverage = DiffCoverage.Pinned

  /** Parse a unified diff and return the changed file paths (the `b/` side of
    * each `+++ b/<path>` header). Filters out `/dev/null` so deletions don't
    * pollute the list. Order matches first appearance in the diff.
    *
    * Only for [[Pinned]], where the diff text is all there is. Diff text can't
    * name every changed file: a binary change and a 100%-similarity rename
    * carry no `+++` header, and for a path with a space the capture includes
    * git's disambiguating trailing tab. [[Sampled]] asks git instead.
    */
  private def extractChangedFiles(diff: String): List[String] =
    "(?m)^\\+\\+\\+ b/(.+)$".r
      .findAllMatchIn(diff)
      .map(_.group(1))
      .filterNot(_ == "/dev/null")
      .toList
      .distinct
