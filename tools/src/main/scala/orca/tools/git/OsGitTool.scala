package orca.tools.git

import orca.{CommitInfo, GitTool, OrcaEvent, OrcaFlowException, Worktree}
import orca.subprocess.QuietProc

/** `GitTool` implementation that shells out to the `git` CLI via os-lib.
  * Contract semantics (commit auto-staging, push upstream setup, diff vs HEAD,
  * worktree branch-exists handling) are specified on the trait; this class
  * handles the subprocess plumbing and the worktree-list parser.
  *
  * `emit` lets the tool publish [[OrcaEvent.Step]]s for the operations the user
  * cares to see in the event log (branch switches, commits, pushes). It's
  * optional — defaults to a no-op so callers that don't yet wire a dispatcher
  * still work.
  */
class OsGitTool(
    workDir: os.Path = os.pwd,
    emit: OrcaEvent => Unit = _ => ()
) extends GitTool:

  def createBranch(name: String): Unit =
    val _ = git("checkout", "-b", name)
    emit(OrcaEvent.Step(s"Switched to a new branch '$name'"))

  def checkout(name: String): Unit =
    val _ = git("checkout", name)
    emit(OrcaEvent.Step(s"Switched to branch '$name'"))

  def checkoutOrCreate(name: String): Unit =
    if currentBranch() == name then
      // Already on the target — no work to do, no event to emit.
      ()
    else
      val exists = git("branch", "--list", name).trim.nonEmpty
      if exists then checkout(name)
      else createBranch(name)

  def ensureClean(stashMessage: String): Boolean =
    val dirty = git("status", "--porcelain").trim.nonEmpty
    if dirty then
      val _ = git("stash", "push", "-u", "-m", stashMessage)
      emit(
        OrcaEvent.Step(
          s"Working tree wasn't clean — stashed pending changes ($stashMessage). Recover with `git stash pop`."
        )
      )
      true
    else false

  def commit(message: String): Unit =
    val _ = git("add", "-A")
    val _ = git("commit", "-m", message)
    emit(OrcaEvent.Step(s"Committed: $message"))

  def push(): Unit =
    // `-u origin HEAD` sets upstream on first push and is a no-op afterwards.
    val _ = git("push", "-u", "origin", "HEAD")
    emit(OrcaEvent.Step("Pushed to origin"))

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

  def addWorktree(path: os.Path, branch: String): Worktree =
    // Check out existing branch if it already exists; otherwise branch off
    // HEAD. `git branch --list <name>` prints the branch when it exists,
    // empty when not.
    val branchExists = git("branch", "--list", branch).trim.nonEmpty
    val cmd =
      if branchExists then Seq("worktree", "add", path.toString, branch)
      else Seq("worktree", "add", "-b", branch, path.toString)
    val _ = git(cmd*)
    emit(OrcaEvent.Step(s"Added worktree at $path on branch '$branch'"))
    Worktree(path, branch)

  def removeWorktree(path: os.Path): Unit =
    val _ = git("worktree", "remove", path.toString)
    emit(OrcaEvent.Step(s"Removed worktree at $path"))

  def listWorktrees(): List[Worktree] =
    OsGitTool.parseWorktreeList(git("worktree", "list", "--porcelain"))

  private def git(args: String*): String =
    // Route through QuietProc so git's stderr ("Switched to a new
    // branch", "Already on 'main'", etc.) is captured rather than
    // leaked to the parent terminal where it would tear the renderer's
    // status row. The branch-state changes themselves still surface in
    // the event log via the OrcaEvent.Step calls in the public methods
    // above; we don't need git's verbose stderr for that.
    val result = QuietProc.call("git" +: args, cwd = workDir)
    if result.exitCode != 0 then
      throw OrcaFlowException(
        s"git ${args.mkString(" ")} failed (exit ${result.exitCode}): ${result.err.text()}"
      )
    result.out.text()

private[orca] object OsGitTool:

  private val WorktreePrefix = "worktree "
  private val BranchPrefix = "branch refs/heads/"

  /** Parse the output of `git worktree list --porcelain`. Entries are separated
    * by blank lines; each entry has `worktree <path>` followed by `HEAD <sha>`
    * and either `branch refs/heads/<name>` or `detached`. Detached-HEAD entries
    * are dropped so callers always get a branch name.
    */
  def parseWorktreeList(output: String): List[Worktree] =
    output
      .split("\n\n")
      .toList
      .flatMap: entry =>
        val lines = entry.linesIterator.toList
        for
          path <- lines.collectFirst {
            case l if l.startsWith(WorktreePrefix) =>
              os.Path(l.stripPrefix(WorktreePrefix))
          }
          branch <- lines.collectFirst {
            case l if l.startsWith(BranchPrefix) => l.stripPrefix(BranchPrefix)
          }
        yield Worktree(path, branch)
