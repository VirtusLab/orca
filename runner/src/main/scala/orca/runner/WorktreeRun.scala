package orca.runner

import orca.OrcaDir
import orca.progress.ProgressStore
import orca.tools.{
  MainCheckoutFailure,
  StartBranchFailure,
  WorktreeAddFailure,
  Worktrees
}

/** Where a `--worktree` run happens: a git worktree of its own, created on
  * first use and reused after.
  *
  * The one place that derives the path. It is keyed on the same prompt hash as
  * the run's progress log, so re-running the same task with `--worktree` lands
  * back in the worktree that holds that log, without anyone re-deriving where
  * that is. (The shell's resume relaunch does not use the flag at all — it runs
  * in the directory the log was found in.)
  */
private[orca] object WorktreeRun:

  /** What is at the derived path. A registered worktree whose directory is gone
    * — what `git clean -xdff` leaves — counts as [[Absent]]: `Worktrees.add`
    * reclaims that entry.
    */
  private enum PathState:
    case Reusable, Occupied, Absent

  /** The directory a `--worktree` run should use, created if it does not exist
    * yet. `invokingDir` is where orca was started, which may itself be a
    * worktree of the repository — the answer is the same either way.
    *
    * `Left` is a message for the user: no repository or no commits to start
    * from, something already occupying the path, or git's own refusal.
    */
  def resolve(
      invokingDir: os.Path,
      userPrompt: String
  ): Either[String, os.Path] =
    Worktrees
      .mainCheckoutOrReason(invokingDir)
      .left
      .map(noMainCheckout)
      .flatMap: mainCheckout =>
        val path =
          OrcaDir.worktreesPath(mainCheckout) /
            ProgressStore.hashPrompt(userPrompt)
        pathState(invokingDir, path) match
          case PathState.Reusable => reuse(mainCheckout, path)
          case PathState.Occupied =>
            Left(
              s"$path already exists but is not a worktree of this " +
                "repository — orca will not take over a directory it did " +
                "not create"
            )
          case PathState.Absent => create(invokingDir, mainCheckout, path)

  /** What to tell the user when no main checkout could be named. Each case gets
    * its own sentence: telling someone whose repository is fine to `git init`
    * is advice for a problem they do not have.
    */
  private def noMainCheckout(failure: MainCheckoutFailure): String =
    failure match
      case MainCheckoutFailure.NotARepository =>
        GitPreconditions.needsRepoWithCommit
      case MainCheckoutFailure.MainWorktreeNotACheckout =>
        "this repository's main worktree is not a checkout (a " +
          "--separate-git-dir or submodule layout) — run --worktree from the " +
          "main checkout instead"
      case MainCheckoutFailure.Unsupported =>
        "orca could not determine this repository's main checkout (git did " +
          "not answer `rev-parse --path-format=absolute`; git 2.31 or newer " +
          "is required)"

  /** An existing registered worktree, made fit to run in: ignore marker back in
    * place (`git clean -xdf` in the main checkout removes it and leaves the
    * worktree it hides), and off a detached HEAD — a create that got half way,
    * `add` having succeeded where the branch step did not. Running detached
    * records the literal "HEAD" as the run's starting branch, which resume
    * refuses as an unsafe ref.
    *
    * Every path that concludes "reuse the worktree at `path`" comes through
    * here, so none of them can skip the repair.
    */
  private def reuse(
      mainCheckout: os.Path,
      path: os.Path
  ): Either[String, os.Path] =
    val _ = OrcaDir.ensureWorktrees(mainCheckout)
    // Anything but a definite branch goes through the repair, unreadable
    // included: skipping it for a tree whose state is unknown is how a run ends
    // up recording the literal "HEAD" as its starting branch.
    if Worktrees.onABranch(path) then Right(path) else bindBranch(path)

  /** Whether `workDir` is a worktree orca made for its own repository — the
    * question the closing summary asks, since a run ends up in one either by
    * the `--worktree` flag or by the shell relaunching a resume where its
    * progress log was found. Reads the layout rather than an input flag, so
    * both routes answer the same.
    */
  def isWorktreeRun(workDir: os.Path): Boolean =
    Worktrees
      .mainCheckout(workDir)
      .exists(main => workDir / os.up == OrcaDir.worktreesPath(main))

  private def pathState(invokingDir: os.Path, path: os.Path): PathState =
    if !os.exists(path) then PathState.Absent
    else if Worktrees.list(invokingDir).contains(path) then PathState.Reusable
    else PathState.Occupied

  private def create(
      invokingDir: os.Path,
      mainCheckout: os.Path,
      path: os.Path
  ): Either[String, os.Path] =
    // The ignore marker goes in before the worktree, never after: until it is
    // there, `git add -A` in the main checkout stages the new worktree as an
    // embedded git repository.
    val _ = OrcaDir.ensureWorktrees(mainCheckout)
    Worktrees.add(invokingDir, path) match
      case Right(())     => bindBranch(path)
      case Left(failure) =>
        // Another orca may have created it between `pathState` and here: the
        // run lock is keyed on the run directory, which does not exist yet, so
        // nothing serialises two processes starting the same task at once. If
        // the path is a registered worktree now, that is the answer the winner
        // got a moment earlier.
        if pathState(invokingDir, path) == PathState.Reusable then
          reuse(mainCheckout, path)
        else
          Left(failure match
            case WorktreeAddFailure.NoCommitsYet =>
              GitPreconditions.needsRepoWithCommit
            case WorktreeAddFailure.GitFailed(message) =>
              s"could not create the worktree at $path: $message"
          )

  /** Put the worktree on the branch named after the same task hash, so a re-run
    * of the task finds its own branch rather than a stranger's. Its refusals
    * say the worktree exists — it does by then, and the next run finds it.
    */
  private def bindBranch(path: os.Path): Either[String, os.Path] =
    val branch = s"orca-worktree-${path.last}"
    Worktrees.startBranch(path, branch) match
      case Right(()) => Right(path)
      case Left(StartBranchFailure.WouldLoseCommits(name)) =>
        Left(
          s"branch '$name' already has commits this run would not start from " +
            s"— orca will not move it; merge it, or delete it with " +
            s"git branch -D $name"
        )
      case Left(StartBranchFailure.GitFailed(message)) =>
        // This runs before the run lock, whose directory is the one being
        // written to, so another orca binding the same branch in the same
        // worktree is a real outcome — and an idempotent one. Proceed only on a
        // DEFINITE branch: a worktree that has gone away answers neither, and
        // treating that as "already done" would run against a path that is no
        // longer a worktree at all.
        if Worktrees.onABranch(path) then Right(path)
        else
          Left(
            s"the worktree at $path exists but could not be put on branch " +
              s"'$branch': $message"
          )
