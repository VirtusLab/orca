package orca.tools

import orca.{CommitInfo, GitTool, OrcaFlowException}

/** Git tool implementation that shells out to the `git` CLI via os-lib.
  * `commit` automatically stages all tracked + untracked changes before
  * committing, matching typical flow-script expectations.
  */
class OsGitTool(workDir: os.Path = os.pwd) extends GitTool:

  def createBranch(name: String): Unit =
    val _ = git("checkout", "-b", name)

  def checkout(name: String): Unit =
    val _ = git("checkout", name)

  def commit(message: String): Unit =
    val _ = git("add", "-A")
    val _ = git("commit", "-m", message)

  def push(): Unit =
    // `-u origin HEAD` sets upstream on first push and is a no-op afterwards.
    val _ = git("push", "-u", "origin", "HEAD")

  def currentBranch(): String =
    git("rev-parse", "--abbrev-ref", "HEAD").trim

  def diff(): String =
    // vs HEAD: show both staged and unstaged changes since the last commit.
    git("diff", "HEAD")

  def log(n: Int): List[CommitInfo] =
    // Fields are separated with the ASCII unit separator (0x1F) so commit
    // messages can contain anything printable without ambiguity.
    val sep = ""
    val fmt = s"%H$sep%s$sep%an"
    val output = git("log", "-n", n.toString, s"--pretty=format:$fmt")
    output.linesIterator
      .filter(_.nonEmpty)
      .map: line =>
        line.split(sep, -1) match
          case Array(hash, msg, author) => CommitInfo(hash, msg, author)
          case _ =>
            throw OrcaFlowException(s"Unexpected git log line: $line")
      .toList

  private def git(args: String*): String =
    val result = os
      .proc("git" +: args)
      .call(cwd = workDir, check = false)
    if result.exitCode != 0 then
      throw OrcaFlowException(
        s"git ${args.mkString(" ")} failed (exit ${result.exitCode}): ${result.err.text()}"
      )
    result.out.text()
