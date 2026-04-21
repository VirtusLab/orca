package orca

case class CommitInfo(hash: String, message: String, author: String)

trait GitTool:
  def createBranch(name: String): Unit
  def checkout(name: String): Unit
  def commit(message: String): Unit
  def push(): Unit
  def currentBranch(): String
  def diff(): String
  def log(n: Int = 10): List[CommitInfo]
