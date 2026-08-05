package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.QuietProc

import scala.util.control.NonFatal

case class CommitInfo(hash: String, message: String, author: String)

/** Which diff semantics [[GitTool.diffVsBase]] should produce.
  *
  *   - [[DiffMode.MergeBase]] (default) — three-dot syntax (`base...HEAD`).
  *     Changes the current branch introduces since it forked off `base`,
  *     ignoring commits `base` gained since the fork (GitHub's PR view).
  *   - [[DiffMode.Direct]] — two-dot syntax (`base..HEAD`). Compares HEAD
  *     directly to `base`'s current tip.
  */
enum DiffMode:
  case MergeBase
  case Direct

/** A linked git worktree — a separate working directory checked out at a
  * specific branch, sharing the main repository's object store.
  */
case class Worktree(path: os.Path, branch: String)

/** One consistent sample of what the next commit would include — see
  * [[GitTool.pendingChanges]]. `newFiles` holds the paths new to the repository
  * — see [[GitTool.untrackedPaths]] — which the stat cannot report and `diff`
  * shows only as new-file hunks.
  */
case class PendingChanges(stat: String, newFiles: List[String], diff: String)

/** How much of one file a change set touched — see
  * [[GitTool.changedFileStats]].
  */
enum FileChange:
  /** Lines added and removed, as `git diff --numstat` counts them. */
  case Lines(added: Int, deleted: Int)

  /** A binary file: git reports that it differs, never by how many lines. */
  case Binary

  /** A file new to the repository, so all of its content is added. Git reports
    * no counts for these — an untracked file has no tracked history to count
    * against.
    */
  case New

/** One path in a change set, with how much of it changed — see
  * [[GitTool.changedFileStats]].
  */
case class ChangedFile(path: String, change: FileChange)

/** Returned in the `Left` of [[GitTool.createBranch]] when a branch by that
  * name already exists. Distinguished from system-level git failures (binary
  * missing, IO error) which surface as thrown `OrcaFlowException`. Subclasses
  * `OrcaFlowException` so callers can `.orThrow` when the case is unexpected.
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
  * for a reason the caller might recover from. Two shapes with different
  * recovery contracts:
  *
  *   - [[PushFailure.NonFastForward]]: the remote branch moved on since the
  *     local history was based (`non-fast-forward` / `fetch first`).
  *     Recoverable by fetching and rebasing.
  *   - [[PushFailure.RemoteDeclined]]: the remote refused the push by policy —
  *     a server-side hook, branch protection, or a required review (e.g.
  *     GitHub's `GH006`) — not history divergence. Rebasing will not help.
  *
  * Other push failures (auth, network, bad refspec) remain thrown as
  * `OrcaFlowException`.
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
    */
  def commitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit

  /** Commit exactly `path`, assuming the caller already staged it (typically
    * via [[forceAdd]]) — unlike [[commitOnly]], this does no `add` of its own.
    * Scoped by the same commit pathspec, so nothing else staged or dirty leaks
    * in. Needed wherever the path must be force-staged to punch through
    * `.gitignore`: a plain `git add` on an already-staged-but-not-yet-tracked
    * ignored path still refuses without `-f`, so [[commitOnly]]'s own add would
    * fail there. Used for the progress-log header commit (ADR 0018 R8), which
    * must land even under a gitignored `.orca/`.
    */
  def commitStaged(path: os.Path, message: String)(using WorkspaceWrite): Unit

  /** Force-stage `path` (`git add -f`), bypassing `.gitignore`. The stage
    * runtime uses this to stage its progress-log file even when the project
    * gitignores `.orca/`, so the log travels with the branch (ADR 0018 §2.1).
    * Always a single explicit path — never a glob or directory.
    */
  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit

  /** Stage `path` (`git add`), respecting `.gitignore`: an ignored path is left
    * unstaged, so the settings-file commit (ADR 0019) never punches a
    * `.orca/`-ignored file into history. Always a single explicit path — never
    * a glob or directory.
    */
  def add(path: os.Path)(using WorkspaceWrite): Unit

  /** Push the current branch, setting upstream on first push. Returns
    * `Left(PushFailure)` when the remote rejected the push for a reason the
    * caller might recover from — see [[PushFailure]] for the two shapes and
    * their differing recovery contracts. Other failures (auth, network) throw.
    */
  def push()(using WorkspaceWrite): Either[PushFailure, Unit]

  def currentBranch(): String

  /** The commit HEAD resolves to, as a full hash. READ-ONLY. Best-effort:
    * `None` when HEAD names no commit (a repository with no history yet) or the
    * probe cannot answer.
    */
  def headCommit(): Option[String]

  /** True when git ignores `relPath` relative to the working directory (`git
    * check-ignore`). READ-ONLY. Best-effort: `false` whenever the probe cannot
    * answer (not a git repo, git unavailable) — callers use this for warnings,
    * never for decisions that must be right.
    */
  def isIgnored(relPath: os.SubPath): Boolean

  /** Best-effort name of the repository's default branch, read from the
    * remote's recorded `origin/HEAD`. READ-ONLY. Returns `None` when there is
    * no remote, `origin/HEAD` is unset, or any error occurs — callers treat
    * that as "no extra protected branch beyond main/master" (ADR 0018).
    */
  def defaultBranch(): Option[String]

  /** True when the current branch has an upstream and `path` (relativized
    * against the working directory) is present in the upstream's tree — a
    * directory, and the working directory itself, count as present just as a
    * file does. READ-ONLY. Best-effort: `false` whenever the probe cannot
    * answer (no upstream, no remote, `path` outside the working directory, any
    * git error) — callers use this only to gate cosmetic work, never for
    * decisions that must be right.
    */
  def upstreamHas(path: os.Path): Boolean

  /** Discard all uncommitted changes, resetting the working tree and index to
    * `HEAD` (`git reset --hard`). Used by the flow failure teardown to drop a
    * failed stage's partial edits while keeping the committed history (and the
    * committed progress log) intact, so a re-run resumes cleanly (ADR 0018
    * §2.5).
    *
    * '''`reset --hard` does NOT remove untracked files''' — a failed stage's
    * newly created files survive in the working tree. They're swept later, into
    * the next run's `ensureClean` stash (`git stash push -u`), alongside any
    * genuine user WIP. A scoped clean here would risk deleting pre-existing
    * untracked files too (blanket `git clean -fd`), so that cleanup is
    * deliberately left to `ensureClean`, not done here.
    */
  def resetHard()(using WorkspaceWrite): Unit

  /** All changes since the last commit (staged and unstaged) anywhere in the
    * repository, excluding `.orca/` bookkeeping. Tracked files only — an
    * untracked file (nothing to diff against) is invisible here. A
    * reviewer-facing consumer that also needs untracked files surfaced wants
    * [[reviewDiff]] instead.
    *
    * The `.orca/` exclusion is load-bearing, not tidiness: once a stage has
    * committed the progress log the file is tracked, and later stage bodies
    * rewrite it mid-flight (persisting session ids). Its compact one-line JSON
    * then shows up as a pair of very long -/+ lines that sort ahead of the real
    * change.
    */
  def diff(): String

  /** `--stat` summary of the same change set as [[diff]]: one line per changed
    * file with its insertion/deletion counts, then the totals line. Describes
    * which files a change touched without carrying any hunk, so it can be sent
    * to a model when the full diff is too large to be worth its tokens. Paths
    * are printed in full — git's default stat width elides leading directories
    * (`.../orca/tools/GitTool.scala`), which defeats the point of naming files.
    */
  def diffStat(): String

  /** Untracked, non-`.orca/` paths anywhere in the repository, relative to the
    * tool's working directory. Untracked directories are recursed into, so an
    * entry is normally one file; a directory git refuses to enter — a nested
    * repository — stays one entry, with a trailing slash. These are the paths
    * [[diff]] can't report — they have no tracked history to diff against — but
    * that a `git add -A` commit would include (a nested repository as a
    * gitlink), so anything describing what is about to be committed needs them
    * alongside the diff. [[reviewDiff]] renders their contents; this is the
    * list itself.
    */
  def untrackedPaths(): List[String]

  /** The change set a reviewer should see: everything [[diff]] reports, PLUS
    * each untracked non-`.orca/` file rendered as a new-file diff (`git diff
    * --no-index` against `/dev/null`) — so a freshly-created file is visible
    * even though it has no tracked history to diff against. Read-only:
    * untracked files are diffed, never staged.
    *
    * `since` is the commit the working tree is compared against. `None` means
    * HEAD — uncommitted work only, which is empty once the work has been
    * committed; pass the commit a unit of work started from (see
    * [[headCommit]]) to see everything it produced either way.
    *
    * A path `--no-index` can't render — a symlink to a directory, or a nested
    * git repository — appears as a line naming the path rather than being
    * dropped silently.
    */
  def reviewDiff(since: Option[String] = None): String

  /** The file paths in the change set [[reviewDiff]] renders: every tracked
    * path git reports as changed since `since` (`since` as in [[reviewDiff]]),
    * plus every [[untrackedPaths]] entry. `.orca/` bookkeeping is excluded;
    * paths are relative to the tool's working directory.
    *
    * The list comes from git, not from parsing diff text, so files a diff body
    * can't show still appear: a binary change, a 100%-similarity rename (at its
    * new path), a deletion, and paths git would otherwise quote.
    */
  def changedFiles(since: Option[String] = None): List[String]

  /** [[changedFiles]], each path carrying how much of it changed. What a caller
    * that has to leave part of a change set out of a prompt tells the reader
    * about the files it left out (see `orca.BoundedDiff`).
    *
    * Untracked paths report [[FileChange.New]] rather than a count: they have
    * no tracked history to count against, and all of their content is new
    * anyway.
    */
  def changedFileStats(since: Option[String] = None): List[ChangedFile]

  /** Everything the next `commit` would include, in the three shapes a caller
    * describing it needs: the [[diffStat]] summary, the [[untrackedPaths]]
    * list, and the [[reviewDiff]] text.
    *
    * Sampled in ONE pass over the working tree, which is the point: calling
    * `untrackedPaths()` and `reviewDiff()` separately samples it twice, and a
    * file created between the two calls appears in one and not the other.
    */
  def pendingChanges(): PendingChanges

  /** Diff of the current branch vs `base`.
    *
    * `mode = MergeBase` (default) returns the cumulative change a PR against
    * `base` would carry (three-dot, merge-base semantics — GitHub's PR view).
    * `mode = Direct` compares HEAD directly to `base`'s tip.
    *
    * Typical bases: `"origin/HEAD"`, `"main"`, `"master"`. `origin/HEAD` may
    * not be set on a freshly `git init`ed repo — see [[defaultBase]] for a
    * probe-with-fallback helper.
    */
  def diffVsBase(base: String, mode: DiffMode = DiffMode.MergeBase): String

  /** Best-effort default base ref for "branch vs main" diffs. Tries
    * `origin/HEAD` first, then falls back to `origin/main` and `origin/master`.
    *
    * Throws `OrcaFlowException` when none of these refs exist — typically the
    * repo has no remote configured, in which case the caller can substitute a
    * local branch name (e.g. `"main"`).
    */
  def defaultBase(): String

  def log(n: Int = 10): List[CommitInfo]

  /** Verify the working tree is clean. If it isn't, `git stash push` with the
    * supplied message and emit a `Step` event so the user can recover the
    * changes later via `git stash pop`. Used by resumable flows that need a
    * known-clean starting state without destroying the user's work-in-progress.
    *
    * The stash-recovery hint rides on the `Step` reaching the run's dispatcher:
    * a custom `GitTool` built without the run's listener loses the hint, and
    * the user never learns to `git stash pop`.
    *
    * Returns `true` if a stash was created, `false` if the tree was already
    * clean.
    */
  def ensureClean(stashMessage: String)(using WorkspaceWrite): Boolean

  /** True when the working tree has uncommitted changes (`git status
    * --porcelain`). READ-ONLY, unlike [[ensureClean]] — never stashes.
    */
  def isDirty(): Boolean

  /** The paths reported by `git status --porcelain` (modified, staged, and
    * untracked), one per entry. READ-ONLY. Used by skip-branch mode's
    * informational notice on a fresh run with a dirty tree (ADR 0018 amendment)
    * — the count, not the parsed content, is what's shown.
    */
  def dirtyPaths(): List[String]

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
  * Contract semantics are specified on the trait; this class handles the
  * subprocess plumbing and the worktree-list parser.
  *
  * `events` publishes [[OrcaEvent.Step]]s for operations shown in the event log
  * (branch switches, commits, pushes). Optional — defaults to
  * `OrcaListener.noop`.
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

  def isDirty(): Boolean = dirtyPaths().nonEmpty

  def dirtyPaths(): List[String] =
    // One porcelain line per path, except a rename ("R  old -> new"), which
    // is one line covering two paths — fine for an informational count.
    git("status", "--porcelain").linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  def ensureClean(stashMessage: String)(using WorkspaceWrite): Boolean =
    val dirty = isDirty()
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

  def commitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit =
    val _ = git("add", "--", path.toString)
    val _ = git("commit", "-m", message, "--", path.toString)
    events.onEvent(OrcaEvent.Step(s"Committed: $message"))

  def commitStaged(path: os.Path, message: String)(using WorkspaceWrite): Unit =
    val _ = git("commit", "-m", message, "--", path.toString)
    events.onEvent(OrcaEvent.Step(s"Committed: $message"))

  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit =
    val _ = git("add", "-f", path.toString)

  def add(path: os.Path)(using WorkspaceWrite): Unit =
    // `git add` exits non-zero when an explicitly named pathspec is
    // gitignored; the contract is to leave such a path unstaged, so the
    // ignored case skips the add instead of failing.
    if !isIgnored(path.subRelativeTo(workDir)) then
      val _ = git("add", "--", path.toString)

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
    // Uses `gitProc` (returns the result) rather than `git` (throws on
    // non-zero) so failure stderr can be inspected to split the recoverable
    // cases (non-fast-forward, remote-declined) from auth/network errors.
    // `pushArgs` appends a last-resort github credential helper (see its doc).
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
      // Order isn't load-bearing: `push()` targets a single ref per call, so
      // its stderr carries at most one rejection reason (divergence vs. policy
      // decline are mutually exclusive for the same ref).
      if OsGitTool.isNonFastForward(stderr) then
        Left(new PushFailure.NonFastForward(stderr.trim))
      else if OsGitTool.isRemoteDeclined(stderr) then
        Left(new PushFailure.RemoteDeclined(stderr.trim))
      else fail("git push", result)

  def currentBranch(): String =
    git("rev-parse", "--abbrev-ref", "HEAD").trim

  def headCommit(): Option[String] = revParse("HEAD")

  /** The hash `ref` resolves to, `None` when it doesn't resolve. `--verify`
    * makes an unresolvable ref a non-zero exit rather than an echo of the ref
    * itself; `--quiet` keeps that off stderr.
    */
  private def revParse(ref: String): Option[String] =
    try
      val result = gitProc(Seq("git", "rev-parse", "--verify", "--quiet", ref))
      if result.exitCode == 0 then
        Some(result.out.text().trim).filter(_.nonEmpty)
      else None
    catch case NonFatal(_) => None

  def isIgnored(relPath: os.SubPath): Boolean =
    // check-ignore exits 0 when the path is ignored, 1 when it isn't, and 128
    // on error (e.g. not a git repo) — only 0 means ignored, so the error
    // cases collapse to false without special-casing.
    try
      gitProc(
        Seq("git", "check-ignore", "-q", "--", relPath.toString)
      ).exitCode == 0
    catch case NonFatal(_) => false

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

  def upstreamHas(path: os.Path): Boolean =
    // `cat-file -e <rev>:<path>` exits 0 only when that path resolves to an
    // object in the revision's tree. Both misses collapse to a non-zero exit:
    // `@{upstream}` failing to resolve (no upstream configured, no remote) and
    // the path being absent from the upstream tree. `subRelativeTo` throws when
    // `path` lies outside the working directory, which the catch absorbs. The
    // `./` prefix is what makes the path cwd-relative (git documents that form
    // explicitly): a bare `<rev>:<path>` is resolved against the repo root,
    // which differs from `workDir` whenever the tool points at a subdirectory.
    try
      val relPath = path.subRelativeTo(workDir)
      gitProc(
        Seq("git", "cat-file", "-e", s"@{upstream}:./$relPath")
      ).exitCode == 0
    catch case NonFatal(_) => false

  def resetHard()(using WorkspaceWrite): Unit =
    val _ = git("reset", "--hard")
    events.onEvent(
      OrcaEvent.Step("Discarded uncommitted changes (reset --hard)")
    )

  def diff(): String = trackedDiff("HEAD")

  private def trackedDiff(since: String): String =
    git(("diff" +: since +: OsGitTool.wholeRepoExceptOrca)*)

  // `--stat=<width>` widens the stat line so the name column holds a full path;
  // 200 clears any path this side of pathological.
  def diffStat(): String =
    git(("diff" +: "--stat=200" +: "HEAD" +: OsGitTool.wholeRepoExceptOrca)*)

  def reviewDiff(since: Option[String]): String =
    withNewFileContents(since.getOrElse("HEAD"), untrackedPaths())

  def changedFiles(since: Option[String]): List[String] =
    changedFileStats(since).map(_.path)

  // `-z` NUL-terminates each record, so a newline or a non-ASCII byte in a name
  // parses unambiguously. It also turns off the C-quoting git would otherwise
  // apply to a tab in a name — and `--numstat` separates its own fields with
  // tabs, so a tabbed path arrives looking like extra fields and only
  // `OsGitTool.parseNumstat` capping the split keeps it whole.
  //
  // `--no-relative` keeps the paths relative to the repository root, which
  // `asWorkDirRelative` assumes: with `diff.relative` set in the repo git prints
  // them relative to `workDir` instead, and the translation then adds `../` hops
  // and names the wrong files.
  def changedFileStats(since: Option[String]): List[ChangedFile] =
    val args =
      "diff" +: "--numstat" +: "-z" +: "--no-relative" +:
        since.getOrElse("HEAD") +: OsGitTool.wholeRepoExceptOrca
    val tracked = OsGitTool
      .parseNumstat(git(args*))
      .map(f => f.copy(path = asWorkDirRelative(f.path)))
    val untracked = untrackedPaths().map(ChangedFile(_, FileChange.New))
    (tracked ++ untracked).distinctBy(_.path)

  def pendingChanges(): PendingChanges =
    val untracked = untrackedPaths()
    PendingChanges(
      stat = diffStat(),
      newFiles = untracked,
      diff = withNewFileContents("HEAD", untracked)
    )

  private def withNewFileContents(
      since: String,
      untracked: List[String]
  ): String =
    (trackedDiff(since) :: untracked.map(untrackedFileDiff)).mkString

  // `--untracked-files=all` recurses into untracked directories so every file
  // inside is listed individually — the default mode lists only the directory.
  // `-z` NUL-delimits records so a path containing a space or newline parses
  // unambiguously.
  def untrackedPaths(): List[String] =
    val orcaDir = s"$workDirPrefix${orca.OrcaDir.Name}"
    git("status", "--porcelain", "--untracked-files=all", "-z")
      .split('\u0000')
      .toList
      .filter(_.startsWith("?? "))
      .map(_.stripPrefix("?? "))
      .filterNot(p => p == orcaDir || p.startsWith(s"$orcaDir/"))
      .map(asWorkDirRelative)

  /** Where `workDir` sits relative to the repository root (`"sub/"`, or `""`
    * when it IS the root). `git status --porcelain` reports paths from the
    * root, while everything else here - the `.orca` exclusion, `--no-index`
    * arguments - is relative to `workDir`, so the two need translating between.
    * Probed once per instance.
    */
  private lazy val workDirPrefix: String =
    val result = gitProc(Seq("git", "rev-parse", "--show-prefix"))
    if result.exitCode == 0 then result.out.text().trim else ""

  /** A repo-root-relative path as `workDir` sees it: inside `workDir` the
    * prefix comes off, above it the path needs `..` hops back up.
    */
  private def asWorkDirRelative(rootRelative: String): String =
    if rootRelative.startsWith(workDirPrefix) then
      rootRelative.drop(workDirPrefix.length)
    else "../" * workDirPrefix.count(_ == '/') + rootRelative

  /** Render an untracked file as a new-file unified diff, without staging it
    * (`add -N` would mutate the index — this doesn't). `git diff --no-index`
    * exits 1 both when the two sides differ — the expected outcome for any real
    * file against `/dev/null` — and when it cannot read the path at all, so
    * stderr rather than the exit code tells the two apart. Reporting the second
    * as success would return an empty diff for a file that does have contents.
    *
    * A path [[undiffableReason]] names is announced rather than diffed, so that
    * one such path does not abort the whole review.
    */
  private def untrackedFileDiff(relPath: String): String =
    undiffableReason(relPath) match
      case Some(reason) => s"# skipped $relPath: $reason\n"
      case None =>
        val result =
          gitProc(Seq("git", "diff", "--no-index", "--", "/dev/null", relPath))
        val differs = result.exitCode == 1 && result.err.text().isEmpty
        if result.exitCode == 0 || differs then result.out.text()
        else fail(s"git diff --no-index -- /dev/null $relPath", result)

  /** Why `git diff --no-index` cannot render this untracked path, or `None` if
    * it can.
    *
    * `git status -uall` reports each of these as a single entry instead of
    * recursing into it. `--no-index` then walks in and pairs `/dev/null` with a
    * path inside (`error: Could not access 'x/null'`), which takes
    * [[untrackedFileDiff]]'s failure branch. A nested repository is recognised
    * by its `.git` entry — a directory in a clone, a file in a linked worktree,
    * hence `os.exists` rather than `os.isDir`.
    *
    * Symlinks to a file or to nothing are diffable, as mode 120000.
    */
  private def undiffableReason(relPath: String): Option[String] =
    val path = workDir / os.RelPath(relPath)
    if os.isLink(path) && os.isDir(path) then Some("symlink to a directory")
    else if os.exists(path / ".git") then Some("nested git repository")
    else None

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

  private def refExists(ref: String): Boolean = revParse(ref).isDefined

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
    // start branch, minus the orca bookkeeping directory, so only substantive
    // code changes appear in the result.
    git(
      ("diff" +: s"$startBranch..$featureBranch" +: OsGitTool.wholeRepoExceptOrca)*
    )

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
    // Route through QuietProc so git's stderr ("Switched to a new branch",
    // etc.) is captured rather than leaked to the parent terminal, where it
    // would tear the renderer's status row. Branch-state changes surface in the
    // event log via the OrcaEvent.Step calls in the public methods above.
    val result = gitProc("git" +: args)
    if result.exitCode != 0 then fail(s"git ${args.mkString(" ")}", result)
    result.out.text()

private[orca] object OsGitTool:

  /** Pathspec arguments scoping a diff to "the whole repository, minus orca's
    * bookkeeping". `:(top)` is what makes it repo-wide: a magic pathspec is
    * resolved against the process cwd, which is `workDir` — without it a tool
    * pointed at a subdirectory would silently miss every change above it. The
    * exclusion stays cwd-relative on purpose, since `.orca/` lives under
    * `workDir`, not under the repository root.
    */
  val wholeRepoExceptOrca: Seq[String] =
    Seq("--", ":(top)", orca.OrcaDir.ExcludePathspec)

  /** The record separator git's `-z` output modes use. */
  private val NUL: Char = '\u0000'

  /** The records of a `git diff --numstat -z`, in git's order.
    *
    * A record is `<added>\t<deleted>\t<path>`, NUL-terminated. A rename ends
    * the record after the tabs and follows it with the old and the new path as
    * two more NUL-terminated fields; the new one is the path the change now
    * lives at, which is what [[GitTool.changedFiles]] reports. A `-` in place
    * of a count marks a binary file, which git reports as differing without
    * saying by how much.
    *
    * The path is everything after the second tab, splitting no further: `-z`
    * turns off the quoting git would otherwise apply to a tab in a name, so a
    * tabbed path arrives raw and would otherwise look like extra fields.
    *
    * Anything that doesn't parse as a record is skipped rather than failing the
    * call: a file list is worth having even if one entry of it is unreadable.
    */
  private[tools] def parseNumstat(raw: String): List[ChangedFile] =
    @scala.annotation.tailrec
    def loop(
        fields: List[String],
        acc: List[ChangedFile]
    ): List[ChangedFile] =
      fields match
        case Nil            => acc.reverse
        case record :: rest =>
          // The limit stops the split at the path, and keeps the empty third
          // field a rename's record ends with — which is what tells the two
          // shapes apart, since git never names an empty path.
          record.split("\t", 3).toList match
            // Rename: the paths are the next two records, old then new.
            case added :: deleted :: "" :: Nil =>
              rest match
                case _ :: renamedTo :: tail =>
                  loop(
                    tail,
                    ChangedFile(renamedTo, change(added, deleted)) :: acc
                  )
                case _ => loop(rest, acc)
            case added :: deleted :: path :: Nil =>
              loop(rest, ChangedFile(path, change(added, deleted)) :: acc)
            case _ => loop(rest, acc)
    loop(raw.split(NUL).toList.filter(_.nonEmpty), Nil)

  private def change(added: String, deleted: String): FileChange =
    (added.toIntOption, deleted.toIntOption) match
      case (Some(a), Some(d)) => FileChange.Lines(a, d)
      case _                  => FileChange.Binary

  // --- Recoverable-failure stderr predicates ---
  //
  // git exits non-zero with a uniform code for many distinct failures, so the
  // only way to split a recoverable case (caller gets a `Left`) from a system
  // failure (we throw) is to match git's human-readable stderr. These strings
  // are git porcelain, not a stable contract, so the matchers are centralised
  // here — named and unit-tested. Each is intentionally lenient (substring) so
  // a wording tweak across git versions doesn't reclassify a recoverable
  // failure as fatal.

  /** True when `git push` stderr indicates the remote branch moved on — see
    * [[PushFailure.NonFastForward]] for the recovery semantics.
    */
  private[tools] def isNonFastForward(stderr: String): Boolean =
    stderr.contains("non-fast-forward") || stderr.contains("fetch first")

  /** True when `git push` stderr indicates the remote refused the push by
    * policy — see [[PushFailure.RemoteDeclined]] for why rebasing won't help.
    */
  private[tools] def isRemoteDeclined(stderr: String): Boolean =
    stderr.contains("hook declined") ||
      stderr.contains("GH006") ||
      stderr.contains("protected branch")

  /** True when `git worktree add` stderr indicates the target path or branch is
    * already a worktree — the recoverable case (see [[addWorktree]]).
    */
  private[tools] def isWorktreeAlreadyPresent(stderr: String): Boolean =
    stderr.contains("already exists") || stderr.contains("already checked out")

  /** Environment that forces git — and any ssh it spawns — to run
    * non-interactively. A flow subprocess has no usable TTY, so a credential or
    * key-passphrase prompt would block the flow forever rather than failing.
    * `GIT_TERMINAL_PROMPT=0` disables the former; `-o BatchMode=yes` on the ssh
    * command disables the latter. The ssh command is appended (not replaced) so
    * a user's custom `GIT_SSH_COMMAND` is preserved.
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
    * has no helper configured. Appended after any config-file helpers, so a
    * user's existing credential setup still wins. When a token is in the
    * environment it is used directly (see [[githubHelper]]), otherwise the `gh`
    * CLI's own auth resolution is used.
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
