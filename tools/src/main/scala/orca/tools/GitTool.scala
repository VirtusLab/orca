package orca.tools

import orca.OrcaFlowException
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.QuietProc
import ox.either.orThrow

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

/** `GitTool` implementation that shells out to the `git` CLI via os-lib.
  * Contract semantics (commit auto-staging, push upstream setup, diff vs HEAD,
  * worktree branch-exists handling) are specified on the trait; this class
  * handles the subprocess plumbing and the worktree-list parser.
  *
  * `events` lets the tool publish [[OrcaEvent.Step]]s for the operations the
  * user cares to see in the event log (branch switches, commits, pushes). It's
  * optional — defaults to `OrcaListener.noop` so callers that don't yet wire a
  * dispatcher still work.
  */
class OsGitTool(
    workDir: os.Path = os.pwd,
    events: OrcaListener = OrcaListener.noop
) extends GitTool:

  def createBranch(name: String): Either[BranchAlreadyExists, Unit] =
    if branchExists(name) then Left(new BranchAlreadyExists(name))
    else
      val _ = git("checkout", "-b", name)
      events.onEvent(OrcaEvent.Step(s"Switched to a new branch '$name'"))
      Right(())

  def checkout(name: String): Either[BranchNotFound, Unit] =
    if !branchExists(name) then Left(new BranchNotFound(name))
    else
      val _ = git("checkout", name)
      events.onEvent(OrcaEvent.Step(s"Switched to branch '$name'"))
      Right(())

  def checkoutOrCreate(name: String): Unit =
    if currentBranch() == name then
      // Already on the target — no work to do, no event to emit.
      ()
    else if branchExists(name) then checkout(name).orThrow
    else createBranch(name).orThrow

  private def branchExists(name: String): Boolean =
    git("branch", "--list", name).trim.nonEmpty

  def ensureClean(stashMessage: String): Boolean =
    val dirty = git("status", "--porcelain").trim.nonEmpty
    if dirty then
      val _ = git("stash", "push", "-u", "-m", stashMessage)
      events.onEvent(
        OrcaEvent.Step(
          s"Working tree wasn't clean — stashed pending changes ($stashMessage). Recover with `git stash pop`."
        )
      )
      true
    else false

  def commit(message: String): Either[NothingToCommit, Unit] =
    val _ = git("add", "-A")
    // `git status --porcelain` after staging is the cheapest "are there
    // changes?" check that doesn't depend on parsing localised git output.
    if git("status", "--porcelain").trim.isEmpty then Left(new NothingToCommit)
    else
      val _ = git("commit", "-m", message)
      events.onEvent(OrcaEvent.Step(s"Committed: $message"))
      Right(())

  def push(): Either[PushRejected, Unit] =
    // `-u origin HEAD` sets upstream on first push and is a no-op afterwards.
    // We need to inspect stderr on failure to distinguish the recoverable
    // "non-fast-forward" case from auth/network errors, so call QuietProc
    // directly rather than going through `git` (which throws on non-zero exit).
    val result = QuietProc.call(
      Seq("git", "push", "-u", "origin", "HEAD"),
      cwd = workDir
    )
    if result.exitCode == 0 then
      events.onEvent(OrcaEvent.Step("Pushed to origin"))
      Right(())
    else
      val stderr = result.err.text()
      if stderr.contains("non-fast-forward") || stderr.contains("rejected") then
        Left(new PushRejected(stderr.trim))
      else
        throw OrcaFlowException(
          s"git push failed (exit ${result.exitCode}): $stderr"
        )

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

  def addWorktree(
      path: os.Path,
      branch: String
  ): Either[WorktreeAddFailed, Worktree] =
    // Check out existing branch if it already exists; otherwise branch off
    // HEAD. `git branch --list <name>` prints the branch when it exists,
    // empty when not.
    val cmd =
      if branchExists(branch) then Seq("worktree", "add", path.toString, branch)
      else Seq("worktree", "add", "-b", branch, path.toString)
    val result = QuietProc.call("git" +: cmd, cwd = workDir)
    if result.exitCode == 0 then
      events.onEvent(
        OrcaEvent.Step(s"Added worktree at $path on branch '$branch'")
      )
      Right(Worktree(path, branch))
    else
      val stderr = result.err.text().trim
      // git surfaces both expected cases ("already exists", "is already
      // checked out") via stderr. Anything else is a system-level failure.
      if stderr.contains("already exists") ||
        stderr.contains("already checked out")
      then Left(new WorktreeAddFailed(path, stderr))
      else
        throw OrcaFlowException(
          s"git worktree add failed (exit ${result.exitCode}): $stderr"
        )

  def removeWorktree(path: os.Path): Either[WorktreeNotFound, Unit] =
    if !listWorktrees().exists(_.path == path) then
      Left(new WorktreeNotFound(path))
    else
      val _ = git("worktree", "remove", path.toString)
      events.onEvent(OrcaEvent.Step(s"Removed worktree at $path"))
      Right(())

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
