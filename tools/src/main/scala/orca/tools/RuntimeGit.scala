package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.gitref.{BranchName, CommitHash}

/** What [[RuntimeGit.discardUncommitted]] does with untracked files, which `git
  * reset --hard` alone never touches.
  */
enum UntrackedFiles:
  /** Delete them. Only correct when everything untracked was created after the
    * tree was last known clean — otherwise this destroys the user's own files.
    */
  case Remove

  case Keep

/** The tracked changes (staged and unstaged) uncommitted on top of `base`,
  * recorded by [[RuntimeGit.snapshotUncommitted]] as a `git stash create`
  * commit that no ref points to. Untracked files and submodule changes are not
  * in it. Git prunes the commit only once it is older than `gc.pruneExpire`
  * (two weeks by default).
  */
final case class UncommittedSnapshot(commit: CommitHash, base: CommitHash)

/** Returned in the `Left` of [[RuntimeGit.snapshotUncommitted]] and
  * [[RuntimeGit.restoreSnapshot]] when git refused, e.g. on an unmerged index
  * or a file in the way.
  */
final class SnapshotFailed(reason: String) extends OrcaFlowException(reason)

/** Returned in the `Left` of [[RuntimeGit.createBranch]] when a branch by that
  * name already exists. Distinguished from system-level git failures (binary
  * missing, IO error) which surface as thrown `OrcaFlowException`. Subclasses
  * `OrcaFlowException` so callers can `.orThrow` when the case is unexpected.
  */
class BranchAlreadyExists(name: BranchName)
    extends OrcaFlowException(s"branch '${name.value}' already exists")

/** Returned in the `Left` of [[RuntimeGit.checkout]] when no branch by that
  * name exists. Same throw-or-handle contract as [[BranchAlreadyExists]].
  */
class BranchNotFound(name: BranchName)
    extends OrcaFlowException(s"branch '${name.value}' not found")

/** Returned in the `Left` of [[RuntimeGit.commit]] when the working tree has no
  * pending changes. Some flows skip-and-continue when nothing changed; others
  * `.orThrow` to abort.
  */
class NothingToCommit
    extends OrcaFlowException("nothing to commit; working tree is clean")

/** The flow runtime's git: [[GitTool]] plus the branch, commit and working-tree
  * operations the runtime alone performs — binding the run's branch, committing
  * each stage, and the setup and teardown around the body. Flow scripts see
  * only [[GitTool]].
  */
trait RuntimeGit extends GitTool:

  /** Create `name` from HEAD and switch to it (`git checkout -b`). Returns
    * `Left(BranchAlreadyExists)` when a branch by that name already exists —
    * the working tree is unchanged in that case. Throws `OrcaFlowException` for
    * system-level failures (git binary, IO).
    */
  def createBranch(name: BranchName)(using
      WorkspaceWrite
  ): Either[BranchAlreadyExists, Unit]

  /** Switch to an existing branch `name` (`git checkout`). Returns
    * `Left(BranchNotFound)` when no such branch exists — the working tree is
    * unchanged. Throws `OrcaFlowException` for system-level failures.
    */
  def checkout(name: BranchName)(using
      WorkspaceWrite
  ): Either[BranchNotFound, Unit]

  /** Detach HEAD at `at` (`git checkout --detach`). Throws `OrcaFlowException`
    * when `at` names no commit, or on system-level failures.
    */
  def checkoutDetached(at: CommitHash)(using WorkspaceWrite): Unit

  /** Stage all tracked + untracked changes, then commit them with `message`.
    * Staging is part of the commit contract. Returns `Left(NothingToCommit)`
    * when the tree is already clean.
    */
  def commit(message: String)(using
      WorkspaceWrite
  ): Either[NothingToCommit, Unit]

  /** Commit exactly the given path: stage it, then `git commit -m <message> --
    * <path>`. The commit pathspec scopes the commit to the single path —
    * anything else dirty or untracked stays out, in contrast to [[commit]]'s
    * `add -A`. Throws `OrcaFlowException` when the path has no changes to
    * commit or on system-level failures.
    *
    * For the runtime's own files (the progress log, the discovered settings),
    * so it announces itself as [[orca.events.OrcaEvent.Bookkeeping]] rather
    * than as a [[orca.events.OrcaEvent.Step]]. Committing the flow's work is
    * [[commit]]'s job.
    */
  def commitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit

  /** Force-stage `path` (`git add -f`) and commit exactly it, scoped by the
    * same commit pathspec as [[commitOnly]] — nothing else staged or dirty
    * leaks in. Use over [[commitOnly]] when the path may be ignored: a plain
    * `git add` under an ignored directory exits non-zero even for a tracked
    * file. Used for the progress-log header commit (ADR 0018 R8). Throws
    * `OrcaFlowException` when the path has no changes to commit or on
    * system-level failures.
    */
  def forceCommitOnly(path: os.Path, message: String)(using
      WorkspaceWrite
  ): Unit

  /** Force-stage `path` (`git add -f`), bypassing `.gitignore`. The stage
    * runtime uses this to stage its progress-log file even when the project
    * gitignores `.orca/`, so the log travels with the branch (ADR 0018 §2.1).
    * Always a single explicit path — never a glob or directory.
    */
  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit

  /** True when a local branch named `name` exists. READ-ONLY. */
  def branchExists(name: BranchName): Boolean

  /** True when git ignores `relPath` relative to the working directory (`git
    * check-ignore`). READ-ONLY. Best-effort: `false` whenever the probe cannot
    * answer (not a git repo, git unavailable), so a wrong `false` must fail
    * loudly in the caller — as a plain `git add` of an ignored path does.
    */
  def isIgnored(relPath: os.SubPath): Boolean

  /** Name of the repository's default branch, read from the remote's recorded
    * `origin/HEAD`. READ-ONLY. `None` when there is no remote or `origin/HEAD`
    * is unset — callers treat that as "no extra protected branch beyond
    * main/master" (ADR 0018). Throws `OrcaFlowException`, naming the remedy,
    * when git fails to answer.
    */
  def defaultBranch(): Option[String]

  /** True when the current branch has an upstream and `path` (relativized
    * against the working directory) is present in the upstream's tree — a
    * directory, and the working directory itself, count as present just as a
    * file does. READ-ONLY. Best-effort: `false` whenever the probe cannot
    * answer (no upstream, no remote, `path` outside the working directory, any
    * git error), so a wrong `false` must be the safe side of the caller's
    * decision.
    */
  def upstreamHas(path: os.Path): Boolean

  /** Discard all uncommitted changes, resetting the working tree and index to
    * `HEAD` (`git reset --hard`). Used by the flow failure teardown to drop a
    * failed stage's partial edits while keeping the committed history (and the
    * committed progress log) intact, so a re-run resumes cleanly (ADR 0018
    * §2.5).
    *
    * `reset --hard` covers tracked files only, so `untracked` decides what
    * happens to files the run newly created — the common shape of agent output.
    * [[UntrackedFiles.Remove]] adds `git clean -fd`, which the caller may only
    * ask for when it knows the tree started clean.
    *
    * The clean never passes `-x`, so gitignored paths (build caches,
    * `.orca/cache/`) are kept, and it always excludes `.orca/` itself: that
    * directory holds other runs' progress logs, and this run's log while no
    * stage has committed it yet — neither is the failed stage's output.
    *
    * One case the exclusion cannot reason about: an empty untracked directory
    * is invisible to `git status` (even `-uall`), so `-d` removes a non-ignored
    * one whether or not the run created it. Empty ignored directories survive.
    */
  def discardUncommitted(untracked: UntrackedFiles)(using
      WorkspaceWrite
  ): Unit

  /** Record the uncommitted tracked changes without touching the working tree,
    * the index or any ref, so [[restoreSnapshot]] can bring them back after
    * [[discardUncommitted]]. `None` when there are none.
    */
  def snapshotUncommitted()(using
      WorkspaceWrite
  ): Either[SnapshotFailed, Option[UncommittedSnapshot]]

  /** Re-apply `snapshot` to the working tree and index (`git stash apply
    * --index`). Applies cleanly when HEAD is `snapshot.base` and the tracked
    * files match it, as they do right after [[discardUncommitted]].
    */
  def restoreSnapshot(snapshot: UncommittedSnapshot)(using
      WorkspaceWrite
  ): Either[SnapshotFailed, Unit]

  /** [[changedFiles]] restricted to tracked paths: exactly the files `git diff
    * <since>` renders.
    *
    * This exists so that a caller which prints a count NEXT TO that command
    * prints one the command can show — orca's closing summary does. Unioning
    * untracked paths in here (as [[changedFiles]] does, for callers that render
    * them separately) would reintroduce the mismatch: a count of 2 above a diff
    * showing 1.
    */
  def trackedChangedFiles(since: CommitHash): List[String]

  /** Verify the working tree is clean. If it isn't, `git stash push` with the
    * supplied message and emit a `Step` event so the user can recover the
    * changes later via `git stash pop`. Used by resumable flows that need a
    * known-clean starting state without destroying the user's work-in-progress.
    *
    * The stash-recovery hint rides on the `Step` reaching the run's dispatcher:
    * a custom `RuntimeGit` built without the run's listener loses the hint, and
    * the user never learns to `git stash pop`.
    */
  def ensureClean(stashMessage: String)(using WorkspaceWrite): Unit

  /** The paths reported by `git status --porcelain` (modified, staged, and
    * untracked), one per entry. READ-ONLY. Used by the cleanliness policy's
    * informational notice on a fresh run that keeps a dirty tree
    * (`--skip-branch` or `--keep-changes`, ADR 0018 amendment) — the count, not
    * the parsed content, is what's shown. Emptiness is also what [[commit]] and
    * [[ensureClean]] decide on, and what the failure teardown's choice of
    * [[UntrackedFiles]] follows from, so narrowing what this reports changes
    * when they commit, when they stash, and whether untracked files are
    * deleted.
    */
  def dirtyPaths(): List[String]

  /** Force-delete a local branch (`git branch -D <name>`). Best-effort — does
    * not throw; failures are silently swallowed so callers can use this in
    * teardown without risking an error cascade. Never deletes the current
    * branch.
    */
  def deleteBranch(name: BranchName)(using WorkspaceWrite): Unit

  /** True when `featureBranch` differs from commit `since` outside `.orca/` —
    * the throwaway-branch check: false means the branch carries only orca
    * bookkeeping.
    */
  def branchHasChangesExcludingOrca(
      since: CommitHash,
      featureBranch: BranchName
  ): Boolean
