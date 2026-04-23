package orca

case class CommitInfo(hash: String, message: String, author: String)

// TODO: implement worktrees integration
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
