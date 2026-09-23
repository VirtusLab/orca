package orca.runner

import orca.{OrcaDir, RunKey}
import orca.gitref.BranchName
import orca.tools.{
  MainCheckoutFailure,
  StartBranchFailure,
  WorktreeAddFailure,
  Worktrees
}

/** Where a `--worktree` run happens: a git worktree of its own, created on
  * first use and reused after.
  *
  * The one place that derives the path. It is keyed on the run's [[RunKey]],
  * like its progress log, so re-running the same task with `--worktree` lands
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
    * `Left` is a sentence for the user, one of:
    *   - no git repository around `invokingDir`, or one with no commit to start
    *     from;
    *   - a repository whose main checkout cannot be named — a
    *     `--separate-git-dir` or submodule layout, or a git too old to answer
    *     the query;
    *   - something other than a worktree of this repository already at the
    *     path;
    *   - the run's own branch already carries commits that putting the worktree
    *     back on it would strand;
    *   - git's own refusal of the create or of the branch step.
    *
    * Throws [[orca.OrcaFlowException]] while another live orca resolves the
    * same task.
    */
  def resolve(
      invokingDir: os.Path,
      key: RunKey
  ): Either[String, os.Path] =
    Worktrees
      .mainCheckoutOrReason(invokingDir)
      .left
      .map(noMainCheckout)
      .flatMap: mainCheckout =>
        val path =
          OrcaDir.worktreesPath(mainCheckout) / key.value
        FlowLock.taskLocked(mainCheckout, key):
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
    * `add` having succeeded where the branch step did not. The run belongs on
    * its own branch, which a re-run of the task finds ([[bindBranch]]).
    *
    * Every path that concludes "reuse the worktree at `path`" comes through
    * here, so none of them can skip the repair. A run already going in the
    * worktree — a resumed one takes no task lock — is on its branch, so the
    * repair never touches it.
    */
  private def reuse(
      mainCheckout: os.Path,
      path: os.Path
  ): Either[String, os.Path] =
    val _ = OrcaDir.ensureWorktrees(mainCheckout)
    // Anything but a definite branch goes through the repair, unreadable
    // included.
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
      case Right(()) => bindBranch(path)
      case Left(WorktreeAddFailure.NoCommitsYet) =>
        Left(GitPreconditions.needsRepoWithCommit)
      case Left(WorktreeAddFailure.GitFailed(message)) =>
        Left(s"could not create the worktree at $path: $message")

  /** Put the worktree on the branch named after the same run key, so a re-run
    * of the task finds its own branch rather than a stranger's. Its refusals
    * say the worktree exists — it does by then, and the next run finds it.
    */
  private def bindBranch(path: os.Path): Either[String, os.Path] =
    BranchName
      .parse(s"orca-worktree-${path.last}")
      .left
      .map(e =>
        s"internal error: no branch name for the worktree at $path ($e)"
      )
      .flatMap(bindTo(path, _))

  private def bindTo(
      path: os.Path,
      branch: BranchName
  ): Either[String, os.Path] =
    Worktrees.startBranch(path, branch) match
      case Right(()) => Right(path)
      case Left(StartBranchFailure.WouldLoseCommits(name)) =>
        Left(
          s"branch '${name.value}' already has commits this run would not " +
            "start from — orca will not move it; merge it, or delete it with " +
            s"git branch -D ${name.value}"
        )
      case Left(StartBranchFailure.GitFailed(message)) =>
        Left(
          s"the worktree at $path exists but could not be put on branch " +
            s"'${branch.value}': $message"
        )
