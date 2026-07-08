package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.QuietProc

import scala.util.control.NonFatal

case class CommitInfo(hash: String, message: String, author: String)

/** Which diff semantics [[GitTool.diffVsBase]] should produce.
  *
  *   - [[DiffMode.MergeBase]] (default) — three-dot syntax (`base...HEAD`).
  *     Matches what GitHub renders in a PR view: changes the current branch
  *     introduces since it forked off `base`, ignoring any commits `base` has
  *     gained since the fork. The right choice for `summarisePr`.
  *   - [[DiffMode.Direct]] — two-dot syntax (`base..HEAD`). Compares HEAD
  *     directly to `base`'s current tip (fast-forward preview, rebase status).
  */
enum DiffMode:
  case MergeBase
  case Direct

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
  * for a reason the caller might recover from. Two shapes, with different
  * recovery contracts:
  *
  *   - [[PushFailure.NonFastForward]]: the remote branch moved on since the
  *     local history was based (`non-fast-forward` / `fetch first`). The caller
  *     can recover by fetching and rebasing.
  *   - [[PushFailure.RemoteDeclined]]: the remote refused the push by policy —
  *     a server-side hook, branch protection, or a required review (e.g.
  *     GitHub's `GH006`) — not by history divergence. Fetching and rebasing
  *     will not help; retrying the same push will fail the same way.
  *
  * Other push failures (auth, network, bad refspec) aren't locally recoverable
  * and remain thrown as `OrcaFlowException`.
  */
sealed abstract class PushFailure(message: String)
    extends OrcaFlowException(message)

object PushFailure:
  final class NonFastForward(reason: String)
      extends PushFailure(s"push rejected (non-fast-forward): $reason")

  final class RemoteDeclined(reason: String)
      extends PushFailure(s"push declined by remote: $reason")

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
  def createBranch(name: String)(using
      WorkspaceWrite
  ): Either[BranchAlreadyExists, Unit]

  /** Switch to an existing branch `name` (`git checkout`). Returns
    * `Left(BranchNotFound)` when no such branch exists — the working tree is
    * unchanged. Throws `OrcaFlowException` for system-level failures.
    */
  def checkout(name: String)(using WorkspaceWrite): Either[BranchNotFound, Unit]

  /** Stage all tracked + untracked changes, then commit them with `message`.
    * Flow scripts rarely want to manage the index separately, so staging is
    * part of the commit contract. Returns `Left(NothingToCommit)` when the tree
    * is already clean.
    */
  def commit(message: String)(using
      WorkspaceWrite
  ): Either[NothingToCommit, Unit]

  /** Force-stage `path` (`git add -f`), bypassing `.gitignore`. The stage
    * runtime uses this to stage its progress-log file even when the project
    * gitignores `.orca/`, so the log travels with the branch (ADR 0018 §2.1).
    * Always a single explicit path — never a glob or directory — so nothing
    * else gitignored is swept in.
    */
  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit

  /** Push the current branch, setting upstream on first push. Returns
    * `Left(PushFailure)` when the remote rejected the push for a reason the
    * caller might recover from — see [[PushFailure]] for the two shapes and
    * their differing recovery contracts. Other failures (auth, network) throw.
    */
  def push()(using WorkspaceWrite): Either[PushFailure, Unit]

  def currentBranch(): String

  /** Best-effort name of the repository's default branch, read from the
    * remote's recorded `origin/HEAD` (`refs/remotes/origin/HEAD` →
    * `origin/<name>` → `<name>`). READ-ONLY; no [[WorkspaceWrite]] needed.
    * Returns `None` when there is no remote, `origin/HEAD` is unset, or any
    * error occurs — callers treat that as "no extra protected branch beyond
    * main/master" (ADR 0018).
    */
  def defaultBranch(): Option[String]

  /** Discard all uncommitted changes, resetting the working tree and index to
    * `HEAD` (`git reset --hard`). Used by the flow failure teardown to drop a
    * failed stage's partial edits while keeping the committed history (and the
    * committed progress log) intact, so a re-run resumes cleanly (ADR 0018
    * §2.5).
    *
    * '''`reset --hard` does NOT remove untracked files''' — only tracked
    * changes (modified/staged/deleted) are discarded. A failed stage's *new*
    * files (the typical shape of agent output: freshly created source/test
    * files) survive this call and remain in the working tree. They aren't lost
    * — the next `flow(...)` invocation's start-of-run `ensureClean` stashes the
    * dirty tree (`git stash push -u`, which does sweep untracked paths), so the
    * leftovers end up co-mingled into that stash alongside any genuine user
    * WIP, rather than discarded outright. If leftovers should instead be
    * deleted at teardown, that needs a scoped clean of run-touched paths (not
    * blanket `git clean -fd`, which would also delete pre-existing untracked
    * user files) — an intentionally separate decision, not made by this method.
    */
  def resetHard()(using WorkspaceWrite): Unit

  /** All changes since the last commit (staged and unstaged). */
  def diff(): String

  /** Diff of the current branch vs `base`.
    *
    * `mode = MergeBase` (default) returns the cumulative change a PR against
    * `base` would carry (three-dot, merge-base semantics — GitHub's PR view).
    * `mode = Direct` compares HEAD directly to `base`'s tip.
    *
    * Typical bases: `"origin/HEAD"` (the remote's default branch, set
    * automatically on `git clone`), `"main"`, `"master"`. For a freshly `git
    * init`ed local repo, `origin/HEAD` may not be set — see [[defaultBase]] for
    * a probe-with-fallback helper.
    */
  def diffVsBase(base: String, mode: DiffMode = DiffMode.MergeBase): String

  /** Best-effort default base ref for "branch vs main" diffs. Tries
    * `origin/HEAD` first (set automatically by `git clone`; resolves to the
    * remote's default branch), then falls back to `origin/main` and
    * `origin/master` — common defaults on a freshly `git init`ed + pushed repo
    * where `origin/HEAD` was never set.
    *
    * Throws `OrcaFlowException` when none of these refs exist — typically means
    * the repo has no remote configured, in which case the caller can substitute
    * a local branch name (e.g. `"main"`).
    */
  def defaultBase(): String

  def log(n: Int = 10): List[CommitInfo]

  /** Verify the working tree is clean. If it isn't, `git stash push` with the
    * supplied message and emit a `Step` event so the user can recover the
    * changes later via `git stash pop`. Used by resumable flows that need a
    * known-clean starting state without silently destroying the user's
    * work-in-progress.
    *
    * The stash-recovery hint rides on the `Step` reaching the run's dispatcher
    * — a custom `GitTool` (e.g. via [[orca.flow]]'s `git` override) built
    * without wiring in the run's listener loses this hint, and the user never
    * learns to `git stash pop`.
    *
    * Returns `true` if a stash was created, `false` if the tree was already
    * clean.
    */
  def ensureClean(stashMessage: String)(using WorkspaceWrite): Boolean

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
  )(using WorkspaceWrite): Either[WorktreeAddFailed, Worktree]

  /** Remove the linked worktree rooted at `path`, also deleting the working
    * directory. Returns `Left(WorktreeNotFound)` when no worktree is registered
    * at that path.
    */
  def removeWorktree(path: os.Path)(using
      WorkspaceWrite
  ): Either[WorktreeNotFound, Unit]

  /** All linked worktrees attached to the repository, including the main one.
    * Detached-HEAD worktrees (no branch) are skipped.
    */
  def listWorktrees(): List[Worktree]

  /** Force-delete a local branch (`git branch -D <name>`). Best-effort — does
    * not throw; failures are silently swallowed so callers can use this in
    * teardown without risking an error cascade. Never deletes the current
    * branch.
    */
  def deleteBranch(name: String)(using WorkspaceWrite): Unit

  /** Diff of `featureBranch` vs `startBranch`, excluding the `.orca/`
    * directory. Used by the throwaway-branch check: an empty result means the
    * feature branch has no substantive changes beyond orca bookkeeping.
    */
  def diffBranchExcludingOrca(
      startBranch: String,
      featureBranch: String
  ): String

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
private[orca] class OsGitTool(
    workDir: os.Path = os.pwd,
    events: OrcaListener = OrcaListener.noop
) extends GitTool:

  def createBranch(name: String)(using
      WorkspaceWrite
  ): Either[BranchAlreadyExists, Unit] =
    if branchExists(name) then Left(new BranchAlreadyExists(name))
    else
      val _ = git("checkout", "-b", name)
      events.onEvent(OrcaEvent.Step(s"Switched to a new branch '$name'"))
      Right(())

  def checkout(
      name: String
  )(using WorkspaceWrite): Either[BranchNotFound, Unit] =
    if !branchExists(name) then Left(new BranchNotFound(name))
    else
      val _ = git("checkout", name)
      events.onEvent(OrcaEvent.Step(s"Switched to branch '$name'"))
      Right(())

  private def branchExists(name: String): Boolean =
    git("branch", "--list", name).trim.nonEmpty

  def ensureClean(stashMessage: String)(using WorkspaceWrite): Boolean =
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

  def commit(message: String)(using
      WorkspaceWrite
  ): Either[NothingToCommit, Unit] =
    val _ = gitWithDiagnostics("add", "-A")
    // `git status --porcelain` after staging is the cheapest "are there
    // changes?" check that doesn't depend on parsing localised git output.
    if git("status", "--porcelain").trim.isEmpty then Left(new NothingToCommit)
    else
      val _ = gitWithDiagnostics("commit", "-m", message)
      events.onEvent(OrcaEvent.Step(s"Committed: $message"))
      Right(())

  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit =
    val _ = git("add", "-f", path.toString)

  /** Like [[git]] but on non-zero exit throws an `OrcaFlowException` enriched
    * with a `git status --porcelain` + `git fsck --no-progress` snapshot. Used
    * by the commit path where a bare stderr line ("unable to read tree X") is
    * not enough to diagnose the actual repo state.
    */
  private def gitWithDiagnostics(args: String*): String =
    val result = gitProc("git" +: args)
    if result.exitCode == 0 then result.out.text()
    else
      throw OrcaFlowException(
        OsGitTool.gitFailureMessage(
          args.mkString(" "),
          result.err.text(),
          gitDiagnostics()
        )
      )

  /** Best-effort collection of `git status --porcelain` + `git fsck
    * --no-progress` for inclusion in a commit-failure exception. Each
    * sub-command is swallowed-and-tagged on failure rather than thrown, so a
    * broken repo can't shadow the original failure with a second one.
    */
  private def gitDiagnostics(): OsGitTool.GitDiagnostics =
    def tryRun(args: String*): String =
      val r = gitProc("git" +: args)
      if r.exitCode == 0 then r.out.text()
      else
        s"<git ${args.mkString(" ")} failed (exit ${r.exitCode}): ${r.err.text().trim}>"
    OsGitTool.GitDiagnostics(
      status = tryRun("status", "--porcelain"),
      fsck = tryRun("fsck", "--no-progress")
    )

  def push()(using WorkspaceWrite): Either[PushFailure, Unit] =
    // `-u origin HEAD` sets upstream on first push and is a no-op afterwards.
    // We need to inspect stderr on failure to distinguish the recoverable
    // cases (non-fast-forward, remote-declined) from auth/network errors, so
    // use `gitProc` (which returns the result) rather than `git` (which
    // throws on non-zero). For a github remote `pushArgs` appends a
    // last-resort credential helper so the push authenticates even when git
    // has none configured (see its doc).
    val originUrl = gitConfigGet("remote.origin.url")
    val envToken = sys.env
      .get("GH_TOKEN")
      .orElse(sys.env.get("GITHUB_TOKEN"))
      .filter(_.nonEmpty)
    val result = gitProc(OsGitTool.pushArgs(originUrl, envToken))
    if result.exitCode == 0 then
      events.onEvent(OrcaEvent.Step("Pushed to origin"))
      Right(())
    else
      val stderr = result.err.text()
      // Checked in this order — non-fast-forward before remote-declined —
      // but the order isn't load-bearing: `push()` targets a single ref per
      // call, so its stderr carries at most one rejection reason (history
      // divergence vs. a policy decline are mutually exclusive causes for
      // the same ref), making a stderr that matches both patterns an
      // unrealistic shape in practice, not a real ambiguity this ordering
      // resolves.
      if OsGitTool.isNonFastForward(stderr) then
        Left(new PushFailure.NonFastForward(stderr.trim))
      else if OsGitTool.isRemoteDeclined(stderr) then
        Left(new PushFailure.RemoteDeclined(stderr.trim))
      else fail("git push", result)

  def currentBranch(): String =
    git("rev-parse", "--abbrev-ref", "HEAD").trim

  def defaultBranch(): Option[String] =
    try
      val result = gitProc(
        Seq("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
      )
      if result.exitCode == 0 then
        // Output is the short ref, e.g. "origin/main"; strip the remote prefix
        // to get the bare branch name callers compare against.
        Some(result.out.text().trim.stripPrefix("origin/")).filter(_.nonEmpty)
      else None
    catch case NonFatal(_) => None

  def resetHard()(using WorkspaceWrite): Unit =
    val _ = git("reset", "--hard")
    events.onEvent(
      OrcaEvent.Step("Discarded uncommitted changes (reset --hard)")
    )

  def diff(): String =
    // vs HEAD: show both staged and unstaged changes since the last commit.
    git("diff", "HEAD")

  def diffVsBase(base: String, mode: DiffMode): String =
    val spec = mode match
      case DiffMode.MergeBase => s"$base...HEAD"
      case DiffMode.Direct    => s"$base..HEAD"
    git("diff", spec)

  def defaultBase(): String =
    resolveOriginHead
      .orElse(List("origin/main", "origin/master").find(refExists))
      .getOrElse(
        throw OrcaFlowException(
          "no default base ref found: tried origin/HEAD, origin/main, origin/master. " +
            "Either set the remote's HEAD (`git remote set-head origin -a`) or " +
            "pass an explicit base to diffVsBase."
        )
      )

  /** Resolve the remote's recorded default branch via `git symbolic-ref`. `-q`
    * suppresses stderr and lets us read the answer off the exit code, so a
    * missing `origin/HEAD` ref becomes a clean `None` rather than a thrown
    * subprocess error.
    */
  private def resolveOriginHead: Option[String] =
    val result = gitProc(
      Seq("git", "symbolic-ref", "-q", "refs/remotes/origin/HEAD")
    )
    if result.exitCode == 0 then
      // Output looks like "refs/remotes/origin/main"; strip the prefix to
      // get the short form callers can pass back into `diff`.
      Some(result.out.text().trim.stripPrefix("refs/remotes/"))
    else None

  private def refExists(ref: String): Boolean =
    gitProc(Seq("git", "rev-parse", "--verify", "--quiet", ref)).exitCode == 0

  def log(n: Int): List[CommitInfo] =
    // Fields are separated with the ASCII unit separator (0x1F) so commit
    // messages can contain anything printable without ambiguity.
    val sep = "\u001f"
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
  )(using WorkspaceWrite): Either[WorktreeAddFailed, Worktree] =
    // Check out existing branch if it already exists; otherwise branch off
    // HEAD. `git branch --list <name>` prints the branch when it exists,
    // empty when not.
    val cmd =
      if branchExists(branch) then Seq("worktree", "add", path.toString, branch)
      else Seq("worktree", "add", "-b", branch, path.toString)
    val result = gitProc("git" +: cmd)
    if result.exitCode == 0 then
      events.onEvent(
        OrcaEvent.Step(s"Added worktree at $path on branch '$branch'")
      )
      Right(Worktree(path, branch))
    else
      val stderr = result.err.text().trim
      if OsGitTool.isWorktreeAlreadyPresent(stderr) then
        Left(new WorktreeAddFailed(path, stderr))
      else fail("git worktree add", result)

  def removeWorktree(
      path: os.Path
  )(using WorkspaceWrite): Either[WorktreeNotFound, Unit] =
    if !listWorktrees().exists(w => samePath(w.path, path)) then
      Left(new WorktreeNotFound(path))
    else
      val _ = git("worktree", "remove", path.toString)
      events.onEvent(OrcaEvent.Step(s"Removed worktree at $path"))
      Right(())

  def listWorktrees(): List[Worktree] =
    OsGitTool.parseWorktreeList(git("worktree", "list", "--porcelain"))

  def deleteBranch(name: String)(using WorkspaceWrite): Unit =
    // Best-effort: swallow all failures so teardown is never blocked by a
    // cosmetic cleanup step. Never attempt to delete the current branch.
    try
      if currentBranch() != name then
        val result = gitProc(Seq("git", "branch", "-D", name))
        if result.exitCode == 0 then
          events.onEvent(OrcaEvent.Step(s"Deleted branch '$name'"))
    catch case NonFatal(_) => ()

  def diffBranchExcludingOrca(
      startBranch: String,
      featureBranch: String
  ): String =
    // Two-dot diff (direct) to see all changes the feature branch has vs the
    // start branch. Pathspec `:(exclude).orca/*` strips the orca bookkeeping
    // directory so only substantive code changes appear in the result.
    git("diff", s"$startBranch..$featureBranch", "--", ".", ":(exclude).orca/*")

  private def samePath(left: os.Path, right: os.Path): Boolean =
    def normalised(path: os.Path): java.nio.file.Path =
      try path.toNIO.toRealPath()
      catch case NonFatal(_) => path.toNIO.toAbsolutePath.normalize()
    normalised(left) == normalised(right)

  /** Run a git subprocess. Every git invocation routes through here so they all
    * carry [[OsGitTool.nonInteractiveEnv]] — no git (or ssh it spawns) can
    * block the flow on an interactive credential or passphrase prompt.
    */
  private def gitProc(args: Seq[String]): os.CommandResult =
    QuietProc.call(args, cwd = workDir, env = OsGitTool.nonInteractiveEnv)

  /** Abort with a uniform `"<label> failed (exit N): <stderr>"` message for an
    * unrecoverable git failure. Callers handle the EXPECTED non-zero exits
    * (rejected push, "already exists") as `Left`s before reaching here.
    */
  private def fail(label: String, result: os.CommandResult): Nothing =
    throw OrcaFlowException(
      s"$label failed (exit ${result.exitCode}): ${result.err.text().trim}"
    )

  /** Read a single git config value (`git config --get`), `None` when unset. */
  private def gitConfigGet(key: String): Option[String] =
    val r = gitProc(Seq("git", "config", "--get", key))
    if r.exitCode == 0 then Some(r.out.text().trim).filter(_.nonEmpty)
    else None

  private def git(args: String*): String =
    // Route through QuietProc so git's stderr ("Switched to a new
    // branch", "Already on 'main'", etc.) is captured rather than
    // leaked to the parent terminal where it would tear the renderer's
    // status row. The branch-state changes themselves still surface in
    // the event log via the OrcaEvent.Step calls in the public methods
    // above; we don't need git's verbose stderr for that.
    val result = gitProc("git" +: args)
    if result.exitCode != 0 then fail(s"git ${args.mkString(" ")}", result)
    result.out.text()

private[orca] object OsGitTool:

  // --- Recoverable-failure stderr predicates ---
  //
  // git exits non-zero with a uniform code for many distinct failures, so the
  // ONLY way to split a recoverable case (caller gets a `Left`) from a system
  // failure (we throw) is to match git's human-readable stderr. These strings
  // are git porcelain, not a stable contract, so the matchers are centralised
  // here — named, documented, and unit-tested — rather than inlined at the call
  // sites, so the fragile coupling lives in one place. Each is intentionally
  // lenient (substring, any matching phrase) so a wording tweak across git
  // versions doesn't silently reclassify a recoverable failure as fatal.

  /** True when `git push` stderr indicates the remote branch moved on since the
    * local history was based (`non-fast-forward` / `fetch first`) — the caller
    * can resolve this by fetching and rebasing. Distinguished from
    * [[isRemoteDeclined]], where rebasing would not help.
    */
  private[tools] def isNonFastForward(stderr: String): Boolean =
    stderr.contains("non-fast-forward") || stderr.contains("fetch first")

  /** True when `git push` stderr indicates the remote refused the push by
    * policy — a server-side hook, branch protection, or a required review (e.g.
    * GitHub's `GH006`) — rather than by history divergence. Fetching and
    * rebasing will not make this push succeed.
    */
  private[tools] def isRemoteDeclined(stderr: String): Boolean =
    stderr.contains("hook declined") ||
      stderr.contains("GH006") ||
      stderr.contains("protected branch")

  /** True when `git worktree add` stderr indicates the target path or branch is
    * already a worktree (`… already exists` / `… is already checked out`) — the
    * recoverable case. Anything else is a system failure.
    */
  private[tools] def isWorktreeAlreadyPresent(stderr: String): Boolean =
    stderr.contains("already exists") || stderr.contains("already checked out")

  /** Environment that forces git — and any ssh it spawns — to run
    * non-interactively. A flow subprocess has no usable TTY, so an HTTPS
    * username/password prompt or an SSH key-passphrase prompt would block the
    * flow forever rather than failing. `GIT_TERMINAL_PROMPT=0` disables the
    * former; `-o BatchMode=yes` on the ssh command disables the latter. The ssh
    * command is appended to (not replaced) so a user's custom `GIT_SSH_COMMAND`
    * is preserved. These merge onto the inherited environment, so `PATH` etc.
    * are unaffected.
    */
  private[tools] val nonInteractiveEnv: Map[String, String] =
    val baseSsh = sys.env.getOrElse("GIT_SSH_COMMAND", "ssh")
    Map(
      "GIT_TERMINAL_PROMPT" -> "0",
      "GIT_SSH_COMMAND" -> s"$baseSsh -o BatchMode=yes"
    )

  /** Host of a git remote URL, for both `scp`-like SSH (`git@host:path`) and
    * URL forms (`scheme://[user@]host[:port]/path`). `None` for local paths or
    * anything without a recognisable host.
    */
  private[tools] def remoteHost(url: String): Option[String] =
    val scpLike = """^[^@/]+@([^:/]+):.*""".r
    val urlLike = """^[a-zA-Z][a-zA-Z0-9+.\-]*://(?:[^@/]+@)?([^:/]+).*""".r
    url.trim match
      case scpLike(host) => Some(host)
      case urlLike(host) => Some(host)
      case _             => None

  private[tools] def isGithubRemote(url: String): Boolean =
    remoteHost(url).contains("github.com")

  /** The `git push` argv. For a github.com origin it appends a credential
    * helper scoped to github.com HTTPS, so the push authenticates even when git
    * has no helper configured. It is *appended* (after any config-file helpers,
    * so a credential setup the user already has still wins) and added *only*
    * for github remotes (a github.com-scoped helper is meaningless elsewhere).
    * When a token is in the environment it is used directly (see
    * [[githubHelper]]), otherwise the `gh` CLI's own auth resolution is used.
    */
  private[tools] def pushArgs(
      originUrl: Option[String],
      envToken: Option[String]
  ): Seq[String] =
    val credential =
      if originUrl.exists(isGithubRemote) then
        Seq(
          "-c",
          s"credential.https://github.com.helper=${githubHelper(envToken.isDefined)}"
        )
      else Nil
    (Seq("git") ++ credential) ++ Seq("push", "-u", "origin", "HEAD")

  /** Shell credential helper for github.com. With a token in the environment it
    * echoes that token (`x-access-token` is GitHub's conventional username for
    * token auth); the token is read from `$GH_TOKEN`/`$GITHUB_TOKEN` at helper
    * runtime, never interpolated here, so it stays out of argv and logs. With
    * no token it defers to the `gh` CLI.
    */
  private def githubHelper(hasEnvToken: Boolean): String =
    if hasEnvToken then
      "!f() { test \"$1\" = get && " +
        "printf 'username=x-access-token\\npassword=%s\\n' " +
        "\"${GH_TOKEN:-$GITHUB_TOKEN}\"; }; f"
    else "!gh auth git-credential"

  private val WorktreePrefix = "worktree "
  private val BranchPrefix = "branch refs/heads/"

  /** Snapshot of repo state captured when a commit fails. `status` is the
    * porcelain listing of what was staged at the moment of failure; `fsck`
    * reports missing/dangling objects when the failure was tree corruption.
    */
  private[tools] case class GitDiagnostics(status: String, fsck: String)

  /** Format a git subprocess failure into the message used by the thrown
    * exception. `cmd` is the argv after `git ` (e.g. `commit -m seed` or `add
    * -A`). Sectioned so the original stderr stays at the top and the
    * diagnostics follow on their own lines.
    */
  private[tools] def gitFailureMessage(
      cmd: String,
      stderr: String,
      diag: GitDiagnostics
  ): String =
    val statusBlock =
      if diag.status.trim.isEmpty then "  (clean)"
      else diag.status.linesIterator.map("  " + _).mkString("\n")
    val fsckBlock =
      if diag.fsck.trim.isEmpty then "  (no issues reported)"
      else diag.fsck.linesIterator.map("  " + _).mkString("\n")
    s"""git $cmd failed: ${stderr.trim}
       |
       |git status --porcelain:
       |$statusBlock
       |
       |git fsck --no-progress:
       |$fsckBlock""".stripMargin

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
