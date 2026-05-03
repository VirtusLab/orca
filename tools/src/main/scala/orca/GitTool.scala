package orca

case class CommitInfo(hash: String, message: String, author: String)

/** A linked git worktree — a separate working directory checked out at a
  * specific branch, sharing the main repository's object store.
  */
case class Worktree(path: os.Path, branch: String)

/** Returned in the `Left` of [[GitTool.createBranch]] when a branch by that
  * name already exists. Distinguished from system-level git failures (binary
  * missing, IO error) which surface as thrown `OrcaFlowException`. Subclasses
  * `OrcaFlowException` so callers can `.orThrow` to recover the throwing
  * behaviour when the case is genuinely unexpected.
  */
class BranchAlreadyExists(name: String)
    extends OrcaFlowException(s"branch '$name' already exists")

/** Returned in the `Left` of [[GitTool.checkout]] when no branch by that name
  * exists. Same throw-or-handle contract as [[BranchAlreadyExists]].
  */
class BranchNotFound(name: String)
    extends OrcaFlowException(s"branch '$name' not found")

/** Returned in the `Left` of [[GitTool.commit]] when the working tree has no
  * pending changes. Some flows skip-and-continue when nothing changed; others
  * `.orThrow` to abort.
  */
class NothingToCommit
    extends OrcaFlowException("nothing to commit; working tree is clean")

/** Returned in the `Left` of [[GitTool.push]] when the remote rejected the push
  * as non-fast-forward (the branch moved on the remote). The caller can recover
  * by fetching and rebasing. Other push failures (auth, network) remain thrown
  * — they aren't locally recoverable.
  */
class PushRejected(reason: String)
    extends OrcaFlowException(s"push rejected: $reason")

/** Returned in the `Left` of [[GitTool.addWorktree]] when the target `path` is
  * already a worktree, or the `branch` is checked out in another worktree.
  */
class WorktreeAddFailed(path: os.Path, reason: String)
    extends OrcaFlowException(s"could not add worktree at $path: $reason")

/** Returned in the `Left` of [[GitTool.removeWorktree]] when no worktree is
  * registered at `path`.
  */
class WorktreeNotFound(path: os.Path)
    extends OrcaFlowException(s"no worktree at $path")

/** Git adapter usable from flow scripts — the handle behind the `git` accessor.
  * Wraps branch, commit, diff, log, and worktree operations against the working
  * repository.
  */
trait GitTool:

  /** Create `name` from HEAD and switch to it (`git checkout -b`). Returns
    * `Left(BranchAlreadyExists)` when a branch by that name already exists —
    * the working tree is unchanged in that case. Throws `OrcaFlowException` for
    * system-level failures (git binary, IO).
    */
  def createBranch(name: String): Either[BranchAlreadyExists, Unit]

  /** Switch to an existing branch `name` (`git checkout`). Returns
    * `Left(BranchNotFound)` when no such branch exists — the working tree is
    * unchanged. Throws `OrcaFlowException` for system-level failures.
    */
  def checkout(name: String): Either[BranchNotFound, Unit]

  /** Switch to `name`, creating it from `HEAD` if it doesn't exist yet.
    * Idempotent: calling on the current branch is a no-op (no `Step` event
    * emitted in that case). Useful for resumable flows that may run against a
    * repo where the branch was already created on a previous attempt.
    */
  def checkoutOrCreate(name: String): Unit

  /** Stage all tracked + untracked changes, then commit them with `message`.
    * Flow scripts rarely want to manage the index separately, so staging is
    * part of the commit contract. Returns `Left(NothingToCommit)` when the tree
    * is already clean.
    */
  def commit(message: String): Either[NothingToCommit, Unit]

  /** Push the current branch, setting upstream on first push. Returns
    * `Left(PushRejected)` when the remote rejected as non-fast-forward (caller
    * can fetch + rebase). Other failures (auth, network) throw.
    */
  def push(): Either[PushRejected, Unit]

  def currentBranch(): String

  /** All changes since the last commit (staged and unstaged). */
  def diff(): String

  def log(n: Int = 10): List[CommitInfo]

  /** Verify the working tree is clean. If it isn't, `git stash push` with the
    * supplied message and emit a `Step` event so the user can recover the
    * changes later via `git stash pop`. Used by resumable flows that need a
    * known-clean starting state without silently destroying the user's
    * work-in-progress.
    *
    * Returns `true` if a stash was created, `false` if the tree was already
    * clean.
    */
  def ensureClean(stashMessage: String): Boolean

  /** Create a linked worktree at `path` on `branch`. If the branch already
    * exists it is checked out in the new worktree; otherwise it is created from
    * `HEAD`. Lets a flow work on several tasks in parallel without
    * branch-hopping in a single directory. Returns `Left(WorktreeAddFailed)`
    * when the path is already a worktree or the branch is checked out
    * elsewhere.
    */
  def addWorktree(
      path: os.Path,
      branch: String
  ): Either[WorktreeAddFailed, Worktree]

  /** Remove the linked worktree rooted at `path`, also deleting the working
    * directory. Returns `Left(WorktreeNotFound)` when no worktree is registered
    * at that path.
    */
  def removeWorktree(path: os.Path): Either[WorktreeNotFound, Unit]

  /** All linked worktrees attached to the repository, including the main one.
    * Detached-HEAD worktrees (no branch) are skipped.
    */
  def listWorktrees(): List[Worktree]
