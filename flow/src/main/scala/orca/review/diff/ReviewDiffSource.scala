package orca.review.diff

import orca.BoundedDiff
import orca.gitref.CommitHash
import orca.tools.GitTool

/** How the loop reads a `orca.review.ReviewDiff` each round. Every answer that
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
    StageSampled(git, base)

  /** Reads `orca.review.ReviewDiff.WholeRun`: everything the working tree has
    * changed since `start`, the commit the run bound at.
    */
  def wholeRun(git: GitTool, start: CommitHash): ReviewDiffSource =
    WholeRunSampled(git, start)

  /** The change set since `base`, bounded to [[BoundedDiff.ReviewThreshold]].
    * Shared by both sampled sources so they can't diverge on what a sample
    * carries.
    */
  private def sampleSince(git: GitTool, base: Option[CommitHash]): DiffSample =
    val changes = git.reviewChanges(base)
    DiffSample(
      BoundedDiff.reviewPayload(changes),
      changes.files.map(_.path),
      changes.sections
    )

  /** Everything the working tree has changed since the enclosing stage began.
    * Private, built only by [[stage]]: how far back `base` reaches and the
    * coverage the reviewer is told are one decision, and a caller free to pair
    * them itself could hand over a whole branch described as one stage's work.
    */
  private case class StageSampled(
      git: GitTool,
      base: Option[CommitHash]
  ) extends ReviewDiffSource:
    def sample(): DiffSample = sampleSince(git, base)
    def coverage: DiffCoverage = DiffCoverage.Stage(base)

  /** Everything the working tree has changed since `start`. Private for the
    * same pairing reason as [[StageSampled]]. Holds the [[CommitHash]] itself,
    * unwrapped at each git call per that type's contract.
    */
  private case class WholeRunSampled(
      git: GitTool,
      start: CommitHash
  ) extends ReviewDiffSource:
    def sample(): DiffSample = sampleSince(git, Some(start))
    def coverage: DiffCoverage = DiffCoverage.Since(start)

  /** Reads `orca.review.ReviewDiff.Pinned`: the caller has already decided what
    * a reviewer should see, so the text is sent as given and only that text can
    * name its files.
    */
  case class Pinned(diff: String) extends ReviewDiffSource:
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
    * git's disambiguating trailing tab. The sampled sources ask git instead.
    */
  private def extractChangedFiles(diff: String): List[String] =
    "(?m)^\\+\\+\\+ b/(.+)$".r
      .findAllMatchIn(diff)
      .map(_.group(1))
      .filterNot(_ == "/dev/null")
      .toList
      .distinct
