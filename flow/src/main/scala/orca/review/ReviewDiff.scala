package orca.review

import orca.{FlowContext, FlowControl}
import orca.events.OrcaEvent
import orca.review.diff.ReviewDiffSource

/** Where a review gets the change set under review. */
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

  /** A diff already written to `path` (repo-relative), which reviewers read
    * themselves; `changedFiles` is the file list the reviewer selector sees.
    * Both stay the same every round, so a resumed reviewer is told there is no
    * new change set, and reviewers are not told what the diff covers or which
    * commit it starts at.
    */
  case InFile(path: String, changedFiles: List[String])

object ReviewDiff:
  /** The source `diff` names, or why there is nothing to review.
    *
    * `WholeRun` checks its starting commit here, at review time, not only when
    * a resume bound the branch: a rebase mid-run — and a fresh run's commit,
    * which binding never checked — would otherwise diff unrelated history.
    * Reviewing some other range would be worse than not reviewing.
    */
  private[review] def resolve(diff: ReviewDiff)(using
      ctx: FlowContext,
      fc: FlowControl
  ): Either[SkippedReview, ReviewDiffSource] =
    diff match
      case ReviewDiff.SampleFromStage =>
        Right(ReviewDiffSource.stage(ctx.git, fc.stageBaseCommit))
      case ReviewDiff.WholeRun =>
        fc.startingCommit.filter(ctx.git.isAncestorOfHead) match
          case Some(c) =>
            ctx.emit(
              OrcaEvent.Step(
                s"reviewing everything changed since commit ${c.short}"
              )
            )
            Right(ReviewDiffSource.wholeRun(ctx.git, c))
          case None => Left(SkippedReview.NoStartingCommit)
      case ReviewDiff.InFile(path, changedFiles) =>
        Right(ReviewDiffSource.inFile(path, changedFiles))
