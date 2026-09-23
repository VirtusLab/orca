package orca

import orca.progress.BranchName

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
  * branch of its own), so [[Worktree]] carries no `Uncommitted` and is not a
  * branch mode: the refused combinations have no representation here, and
  * [[RunTarget.from]] — the only way in from raw flags — is where they are
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

  /** The flags [[OrcaArgs]] parses back, in the order the shell appends them
    * after `--` when it spawns a flow child. Rendering lives next to the parser
    * so both directions of one flag spelling are in a single file.
    */
  def toArgv: Seq[String] = this match
    case NewBranch(Uncommitted.Stash)     => Nil
    case NewBranch(Uncommitted.Keep)      => Seq("--keep-changes")
    case CurrentBranch(Uncommitted.Stash) => Seq("--skip-branch")
    case CurrentBranch(Uncommitted.Keep) =>
      Seq("--skip-branch", "--keep-changes")
    case Worktree => Seq("--worktree")

object RunTarget:

  /** Renders `--branch` as [[OrcaArgs]] parses it; the shell appends it next to
    * [[RunTarget.toArgv]]'s flags.
    */
  def branchArgv(branch: Option[BranchName]): Seq[String] =
    branch.toList.flatMap(name => Seq("--branch", name.value))

  /** Refuses a `--branch` name on a target that creates no branch
    * ([[CurrentBranch]]), with the message [[from]] gives the same pair.
    */
  def refuseBranch(
      target: RunTarget,
      branch: Option[BranchName]
  ): Either[String, Unit] =
    Either.cond(
      !(target.skipBranch && branch.isDefined),
      (),
      skipBranchWithBranchRefusal
    )

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

  /** The single conversion from the raw flags an argv parser produces: a flow's
    * own argv ([[OrcaArgs.parse]]) and `orca run`'s (the shell has its own
    * parser for the same flags) both come through here, so neither the wording
    * of a refusal nor the set of refused pairs can drift. A refused pair is a
    * message, never a value — which is what keeps it out of every type below
    * this point.
    */
  def from(
      worktree: Boolean,
      skipBranch: Boolean,
      keepChanges: Boolean,
      branch: Option[BranchName]
  ): Either[String, RunTarget] =
    val uncommitted =
      if keepChanges then Uncommitted.Keep else Uncommitted.Stash
    if skipBranch && branch.isDefined then Left(skipBranchWithBranchRefusal)
    else if !worktree then
      Right(
        if skipBranch then CurrentBranch(uncommitted)
        else NewBranch(uncommitted)
      )
    else if skipBranch then Left(worktreeWithSkipBranchRefusal)
    else if keepChanges then Left(worktreeWithKeepChangesRefusal)
    else Right(Worktree)
