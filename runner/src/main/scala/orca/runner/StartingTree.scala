package orca.runner

import orca.WorkspaceWrite
import orca.events.OrcaEvent
import orca.tools.{GitTool, UncommittedSnapshot, UntrackedFiles}

/** What the working tree held when the body started, which decides what failure
  * teardown may delete and what it puts back.
  */
private[orca] enum StartingTree:
  /** Clean, or stashed clean by setup: every untracked file is the run's. */
  case Clean

  /** Setup left the user's uncommitted files in place (a fresh run under
    * `--skip-branch`, `--keep-changes`, or an interactive keep answer).
    * `tracked` holds their tracked changes, when there are any.
    */
  case Kept(tracked: Option[UncommittedSnapshot])

  /** Orca cannot tell kept untracked files from the run's, so it deletes them
    * only from a clean start.
    */
  def untracked: UntrackedFiles = this match
    case Clean   => UntrackedFiles.Remove
    case Kept(_) => UntrackedFiles.Keep

  /** Put back the kept tracked changes, run after failure teardown's reset.
    * Once HEAD has moved past the snapshot's base, a commit (normally the first
    * stage's) may already carry them, so the snapshot is only named for manual
    * recovery.
    */
  def restore(git: GitTool, emit: OrcaEvent => Unit)(using
      WorkspaceWrite
  ): Unit = this match
    case Kept(Some(kept)) =>
      val recoverCommand = s"`git stash apply ${kept.commit.value}`"
      if git.headCommit().contains(kept.base) then
        git.restoreSnapshot(kept) match
          case Right(()) =>
            emit(
              OrcaEvent.Step(
                "restored the changes you kept at the start; re-running " +
                  "stashes them before it resumes"
              )
            )
          case Left(failed) =>
            emit(
              OrcaEvent.Step(
                "warning: could not restore the changes you kept at the " +
                  s"start (${failed.getMessage}); recover them with " +
                  recoverCommand
              )
            )
      else
        emit(
          OrcaEvent.Step(
            "the changes you kept at the start are in the run's commits; if " +
              s"any are missing, recover them with $recoverCommand"
          )
        )
    case Kept(None) | Clean => ()

private[orca] object StartingTree:

  /** The starting tree for setup's `untracked` verdict. Runs after setup's last
    * commit, so a snapshot's base is where HEAD stays until the first stage
    * commits. A git refusal (e.g. an unmerged index) leaves kept tracked
    * changes unprotected, and says so.
    */
  def capture(
      untracked: UntrackedFiles,
      git: GitTool,
      emit: OrcaEvent => Unit
  )(using WorkspaceWrite): StartingTree =
    untracked match
      case UntrackedFiles.Remove => Clean
      case UntrackedFiles.Keep =>
        git.snapshotUncommitted() match
          case Right(snapshot) => Kept(snapshot)
          case Left(failed) =>
            emit(
              OrcaEvent.Step(
                "warning: could not snapshot the kept changes " +
                  s"(${failed.getMessage}) — a failure before the first " +
                  "stage commit will discard kept edits to tracked files"
              )
            )
            Kept(None)
