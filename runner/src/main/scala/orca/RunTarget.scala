package orca

import orca.gitref.BranchName

/** What a run does with uncommitted and untracked files it finds in the working
  * tree at start (`--keep-changes` asks for [[Uncommitted.Keep]]).
  */
enum Uncommitted:
  /** Stash them, so the run starts from committed content. */
  case Stash

  /** Leave them in place for the flow to work on and commit. */
  case Keep

/** Where a run's work goes: which branch it commits onto, and — in the cases
  * where the question arises at all — what happens to uncommitted files.
  *
  * This is what `--skip-branch`, `--keep-changes` and `--worktree` become once
  * argv is parsed. `--worktree` combines with neither of the other two (a
  * worktree is created from a commit, so it starts clean and checks out a
  * branch of its own), so [[RunTarget.Worktree]] carries no `Uncommitted` and
  * is not a branch mode: the refused combinations have no representation here,
  * and [[RunTarget.from]] — the only way in from raw flags — is where they are
  * refused.
  */
enum RunTarget:
  /** A branch orca creates in the invoking checkout — the default. */
  case NewBranch(uncommitted: Uncommitted)

  /** The branch checked out now; the flow commits onto it (`--skip-branch`). */
  case CurrentBranch(uncommitted: Uncommitted)

  /** A separate checkout under `.orca/worktrees/` (`--worktree`). */
  case Worktree

  // Views for the two setup decisions that turn on a single axis (which branch
  // to bind, what to do with a dirty tree). Derived, so no caller can set one
  // without the case that implies it.
  def skipBranch: Boolean = this match
    case CurrentBranch(_)        => true
    case NewBranch(_) | Worktree => false

  def keepChanges: Boolean = this match
    case NewBranch(uncommitted)     => uncommitted == Uncommitted.Keep
    case CurrentBranch(uncommitted) => uncommitted == Uncommitted.Keep
    case Worktree                   => false

object RunTarget:

  private val skipBranchWithBranchRefusal: String =
    "--branch cannot be combined with --skip-branch: --skip-branch runs on " +
      "the branch checked out now, so there is no branch to create. Drop " +
      "--skip-branch to create the named branch, or check that branch out " +
      "and pass only --skip-branch"

  private val worktreeWithSkipBranchRefusal: String =
    "--worktree cannot be combined with --skip-branch: --skip-branch runs on " +
      "the branch checked out now, and git will not check that branch out a " +
      "second time in a new worktree"

  private val worktreeWithKeepChangesRefusal: String =
    "--worktree cannot be combined with --keep-changes: --keep-changes works " +
      "on uncommitted files, which stay behind in the invoking checkout — a " +
      "worktree is created from a commit and starts clean"

  /** The single conversion from the raw flags [[RawArgs]] parses, for a flow's
    * own argv and `orca run`'s alike. A refused pair is a message, never a
    * value.
    */
  private[orca] def from(
      worktree: Boolean,
      skipBranch: Boolean,
      keepChanges: Boolean,
      branch: Option[BranchName]
  ): Either[String, RunTarget] =
    val uncommitted =
      if keepChanges then Uncommitted.Keep else Uncommitted.Stash
    if !worktree then
      if !skipBranch then Right(NewBranch(uncommitted))
      else if branch.isDefined then Left(skipBranchWithBranchRefusal)
      else Right(CurrentBranch(uncommitted))
    else if skipBranch then Left(worktreeWithSkipBranchRefusal)
    else if keepChanges then Left(worktreeWithKeepChangesRefusal)
    else Right(Worktree)
