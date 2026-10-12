package orca.review.diff

import orca.BoundedDiff
import orca.gitref.CommitHash
import orca.tools.GitTool

/** How a review reads its change set each round. Every answer that depends on
  * where the diff came from lives here, so no consumer re-decides it.
  */
private[review] sealed trait ReviewDiffSource:
  /** This round's change set. A sampled source re-samples each round, so every
    * reviewer that round sees the fixer's later edits.
    */
  def sample(): DiffSample

  /** How far back [[sample]] reaches. */
  def coverage: DiffCoverage

private[review] object ReviewDiffSource:
  /** Everything the working tree has changed since the enclosing stage began
    * (ADR 0018 §2.1), at `base`.
    */
  def stage(git: GitTool, base: Option[CommitHash]): ReviewDiffSource =
    Sampled(git, DiffCoverage.Stage(base))

  /** Everything the working tree has changed since `start`, the commit the run
    * bound at.
    */
  def wholeRun(git: GitTool, start: CommitHash): ReviewDiffSource =
    Sampled(git, DiffCoverage.Since(start))

  /** A diff already written to the repo-relative `path`, the same every round.
    */
  def inFile(path: String, changedFiles: List[String]): ReviewDiffSource =
    InFile(DiffSample(DiffText.InFile(path), changedFiles, Map.empty))

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
        DiffText.Inline(BoundedDiff.reviewPayload(changes)),
        changes.files.map(_.path),
        changes.sections
      )

  /** No sections: the sample is the same every round, so it never reaches a cut
    * that would need them.
    */
  private case class InFile(fixed: DiffSample) extends ReviewDiffSource:
    def sample(): DiffSample = fixed
    def coverage: DiffCoverage = DiffCoverage.Fixed
