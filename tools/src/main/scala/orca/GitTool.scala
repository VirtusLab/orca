package orca

case class CommitInfo(hash: String, message: String, author: String)

/** A linked git worktree — a separate working directory checked out at a
  * specific branch, sharing the main repository's object store.
  */
case class Worktree(path: os.Path, branch: String)

/** Git adapter usable from flow scripts — the handle behind the `git` accessor.
  * Wraps branch, commit, diff, log, and worktree operations against the working
  * repository.
  */
trait GitTool:
  def createBranch(name: String): Unit
  def checkout(name: String): Unit

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
