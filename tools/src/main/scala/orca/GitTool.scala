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
    * part of the commit contract.
    */
  def commit(message: String): Unit

  /** Push the current branch, setting upstream on first push. */
  def push(): Unit

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
    * branch-hopping in a single directory.
    */
  def addWorktree(path: os.Path, branch: String): Worktree

  /** Remove the linked worktree rooted at `path`, also deleting the working
    * directory.
    */
  def removeWorktree(path: os.Path): Unit

  /** All linked worktrees attached to the repository, including the main one.
    * Detached-HEAD worktrees (no branch) are skipped.
    */
  def listWorktrees(): List[Worktree]
