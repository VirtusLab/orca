package orca.runner

import orca.OrcaDir
import orca.progress.ProgressStore
import orca.tools.{WorktreeAddFailure, Worktrees}

/** Where a `--worktree` run happens: a git worktree of its own, created on
  * first use and reused after.
  *
  * The one place that derives the path. It is keyed on the same prompt hash as
  * the run's progress log, so re-running a task — including the shell's resume
  * relaunch, which only passes `--worktree` and the same text again — lands
  * back in the worktree that holds that log, without anyone re-deriving where
  * that is.
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
      .mainCheckout(invokingDir)
      .toRight(FlowLifecycle.noCommitsMessage)
      .flatMap: mainCheckout =>
        val path =
          OrcaDir.worktreesPath(mainCheckout) /
            ProgressStore.hashPrompt(userPrompt)
        pathState(invokingDir, path) match
          case PathState.Reusable =>
            // Rewritten on reuse as well: `git clean -xdf` in the main checkout
            // deletes the marker and leaves the worktree it hides.
            val _ = OrcaDir.ensureWorktrees(mainCheckout)
            Right(path)
          case PathState.Occupied =>
            Left(
              s"$path already exists but is not a worktree of this " +
                "repository — orca will not take over a directory it did not " +
                "create"
            )
          case PathState.Absent => create(invokingDir, mainCheckout, path)

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
      case Right(()) =>
        // git leaves a new worktree detached, and a detached checkout's current
        // branch reads back as the literal "HEAD" — which the run would record
        // as its progress header's starting branch, and which resume then
        // refuses as an unsafe ref, leaving the run neither resumable nor
        // restartable. So it starts on a branch of its own, named after the
        // same task hash; the run's feature branch is cut from there as usual.
        Worktrees
          .startBranch(path, s"orca-worktree-${path.last}")
          .map(_ => path)
          .left
          .map(message => s"could not create the worktree at $path: $message")
      case Left(WorktreeAddFailure.NoCommitsYet) =>
        Left(FlowLifecycle.noCommitsMessage)
      case Left(WorktreeAddFailure.GitFailed(message)) =>
        Left(s"could not create the worktree at $path: $message")
