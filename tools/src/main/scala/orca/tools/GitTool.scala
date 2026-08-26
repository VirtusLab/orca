package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{CappedResult, QuietProc}
import ox.either
import ox.either.ok

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** How much of a commit [[GitTool.show]] renders. */
enum ShowDetail:
  /** Message plus the full diff. */
  case Full

  /** Message plus `--stat`: which files changed and by how much, no hunks. */
  case StatOnly

/** What [[GitTool.discardUncommitted]] does with untracked files, which `git
  * reset --hard` alone never touches.
  */
enum UntrackedFiles:
  /** Delete them. Only correct when everything untracked was created after the
    * tree was last known clean — otherwise this destroys the user's own files.
    */
  case Remove

  case Keep

/** Why a [[GitTool.show]] / [[GitTool.fileAt]] read did not happen. Returned in
  * a `Left` rather than thrown: these reads take agent-supplied arguments, and
  * a bad one is an answer to relay, not a flow failure. Subclasses
  * `OrcaFlowException` so a caller that considers the case unexpected can
  * `.orThrow`, matching [[PushFailure]].
  */
sealed abstract class GitReadFailed(message: String)
    extends OrcaFlowException(message)

object GitReadFailed:
  final class InvalidRev(rev: String)
      extends GitReadFailed(
        s"'$rev' is not a single revision (letters, digits, '.', '_', '/', " +
          "'-', '@', '^', '~', '{', '}', no range '..' / '^-' / '^@', not " +
          "starting with a dash or a '^')"
      )

  final class InvalidPath(path: String)
      extends GitReadFailed(s"'$path' is not a repository-relative path")

  /** The read has no usable answer. `detail` is git's own message when git
    * refused (an unknown revision, a path missing from the commit), and orca's
    * when git succeeded but the result is unusable — [[GitTool.show]] finding
    * no change for the requested paths, [[GitTool.fileAt]] past its size limit.
    */
  final class Refused(detail: String) extends GitReadFailed(detail)

/** Argument validation for the agent-facing git reads ([[GitTool.show]],
  * [[GitTool.fileAt]]). Both build an argv orca hands to git directly, so the
  * only thing to defend against is an argument that git would read as something
  * other than a value.
  */
private[tools] object GitRead:
  private val RevPattern = """[A-Za-z0-9._/@^~{}-]+""".r

  /** Range operators [[RevPattern]] admits, in the middle of a revision: `x^-`
    * is `x^..x` — on a merge, the whole merged branch — and `x^@` is every
    * parent. `git` forbids all three in a refname, so rejecting them costs
    * nothing.
    */
  private val RangeOperators = List("..", "^-", "^@")

  /** True when `value` names anything other than one commit. A leading `^`
    * excludes rather than names: `git show ^HEAD` exits 0 having printed
    * nothing, so without this an agent gets a blank answer it cannot tell from
    * an empty commit.
    */
  private def isRange(value: String): Boolean =
    value.startsWith("^") || RangeOperators.exists(value.contains)

  /** A single ref or sha, in any of the spellings the tools advertise:
    * `HEAD~1`, `HEAD^`, `@` and `HEAD@{1}` all name one commit, and
    * `git_file_at`'s description asks for a file "before the change under
    * review", which is what `HEAD~1` is for. `x^{/text}` searches history but
    * still resolves to one commit, so it is allowed.
    *
    * The leading-dash rejection stops a revision position being read as a flag;
    * callers additionally pass `--end-of-options`.
    */
  def rev(value: String): Either[GitReadFailed, String] =
    if RevPattern.matches(value) && !value.startsWith("-") && !isRange(value)
    then Right(value)
    else Left(new GitReadFailed.InvalidRev(value))

  /** A repository-relative path: neither absolute, nor climbing out via `..`,
    * nor a magic pathspec. Git reads any leading `:` as magic — `:(exclude)`
    * inverts the request and `:(top)` re-anchors the pathspec at the repository
    * root — and no plain path is addressable that way, so rejecting the prefix
    * costs nothing.
    */
  def path(value: String): Either[GitReadFailed, String] =
    val segments = value.split('/').toList
    if value.nonEmpty && !value.startsWith("/") && !value.startsWith("-") &&
      !value.startsWith(":") && !segments.contains("..")
    then Right(value)
    else Left(new GitReadFailed.InvalidPath(value))

/** What the next commit would include — see [[GitTool.pendingChanges]].
  * `newFiles` holds the paths new to the repository, which the stat cannot
  * report and `diff` shows only as new-file hunks.
  */
case class PendingChanges(stat: String, newFiles: List[String], diff: String)

/** How much of one file a change set touched — see [[GitTool.reviewChanges]].
  */
enum FileChange:
  /** Lines added and removed, as `git diff --numstat` counts them. Both are
    * zero when the content itself did not change — a mode change, a pure
    * rename, or a file that became tracked while empty.
    */
  case Lines(added: Int, deleted: Int)

  /** A binary file: git reports that it differs, never by how many lines. */
  case Binary

  /** A file new to the repository, so all of its content is added. Git reports
    * no counts for these — an untracked file has no tracked history to count
    * against.
    */
  case New

/** One path in a change set, with how much of it changed — see
  * [[GitTool.reviewChanges]].
  */
case class ChangedFile(path: String, change: FileChange)

/** The change set a reviewer sees — see [[GitTool.reviewChanges]]. `files`
  * names every path in it, including the ones `diff` cannot show.
  */
case class ReviewSample(diff: String, files: List[ChangedFile])

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

/** Git adapter usable from flow scripts — the handle behind the `git` accessor.
  * Wraps branch, commit, and diff operations against the working repository.
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

  /** Force-stage `path` (`git add -f`) and commit exactly it, scoped by the
    * same commit pathspec as [[commitOnly]] — nothing else staged or dirty
    * leaks in. Use over [[commitOnly]] when the path may be ignored: a plain
    * `git add` under an ignored directory exits non-zero even for a tracked
    * file. Used for the progress-log header commit (ADR 0018 R8). Throws
    * `OrcaFlowException` when the path has no changes to commit or on
    * system-level failures.
    */
  def forceCommitOnly(path: os.Path, message: String)(using
      WorkspaceWrite
  ): Unit

  /** Force-stage `path` (`git add -f`), bypassing `.gitignore`. The stage
    * runtime uses this to stage its progress-log file even when the project
    * gitignores `.orca/`, so the log travels with the branch (ADR 0018 §2.1).
    * Always a single explicit path — never a glob or directory.
    */
  def forceAdd(path: os.Path)(using WorkspaceWrite): Unit

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

  /** True when `rev` both resolves in this repository and is an ancestor of
    * HEAD — i.e. usable as a diff base for "everything since `rev`". READ-ONLY.
    * Best-effort: `false` whenever the probe cannot answer, so an unknown
    * answer reads as "not a usable base" rather than producing a wrong range.
    */
  def isAncestorOfHead(rev: String): Boolean

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
    * `reset --hard` covers tracked files only, so `untracked` decides what
    * happens to files the run newly created — the common shape of agent output.
    * [[UntrackedFiles.Remove]] adds `git clean -fd`, which the caller may only
    * ask for when it knows the tree started clean.
    *
    * The clean never passes `-x`, so gitignored paths (build caches,
    * `.orca/cache/`) are kept, and it always excludes `.orca/` itself: that
    * directory holds other runs' progress logs, and this run's log while no
    * stage has committed it yet — neither is the failed stage's output.
    *
    * One case the exclusion cannot reason about: an empty untracked directory
    * is invisible to `git status` (even `-uall`), so `-d` removes a non-ignored
    * one whether or not the run created it. Empty ignored directories survive.
    */
  def discardUncommitted(untracked: UntrackedFiles)(using
      WorkspaceWrite
  ): Unit

  /** All changes since the last commit (staged and unstaged) anywhere in the
    * repository, excluding `.orca/` bookkeeping. Tracked files only — an
    * untracked file (nothing to diff against) is invisible here. A
    * reviewer-facing consumer that also needs untracked files surfaced wants
    * [[reviewChanges]] instead; a caller after everything a branch produced
    * wants [[diffVsBase]], since this is empty once the work is committed.
    *
    * The `.orca/` exclusion is load-bearing, not tidiness: once a stage has
    * committed the progress log the file is tracked, and later stage bodies
    * rewrite it mid-flight (persisting session ids). Its compact one-line JSON
    * then shows up as a pair of very long -/+ lines that sort ahead of the real
    * change.
    *
    * Capped at `OsGitTool.MaxReadBytes` and marked where it was cut.
    */
  def uncommittedDiff(): String

  /** The file paths in the change set [[reviewChanges]] renders: every tracked
    * path git reports as changed since `since` (`since` as in
    * [[reviewChanges]]), plus every untracked path. `.orca/` bookkeeping is
    * excluded from both; paths are relative to the tool's working directory.
    *
    * The list comes from git, not from parsing diff text, so files a diff body
    * can't show still appear: a binary change, a 100%-similarity rename (at its
    * new path), a deletion, and paths git would otherwise quote.
    */
  def changedFiles(since: Option[String] = None): List[String]

  /** The change set a reviewer should see, as diff text and as the list of
    * paths in it — what a caller rendering a bounded diff needs, since it has
    * to name the files its diff leaves out (see `orca.BoundedDiff`).
    *
    * The diff is everything [[uncommittedDiff]] reports, PLUS each untracked
    * non-`.orca/` file rendered as a new-file diff (`git diff --no-index`
    * against `/dev/null`), so a freshly-created file is visible even though it
    * has no tracked history to diff against. Read-only: untracked files are
    * diffed, never staged. A path `--no-index` can't render — a symlink to a
    * directory, or a nested git repository — appears as a line naming the path
    * rather than being dropped silently. Such a path is still in `files`,
    * reported as [[FileChange.New]] like every untracked path: they have no
    * tracked history to count lines against.
    *
    * `since` is the commit the working tree is compared against. `None` means
    * HEAD — uncommitted work only, which is empty once the work has been
    * committed; pass the commit a unit of work started from (see
    * [[headCommit]]) to see everything it produced either way.
    *
    * The UNTRACKED set is sampled once for both projections, so a file created
    * mid-call cannot land in one and not the other. Tracked changes are read
    * per projection (a patch diff and a `--numstat` diff), so a tracked file
    * written between those two reads can still differ across them.
    *
    * The diff text is bounded: untracked files stop being rendered once
    * `OsGitTool.MaxReadBytes` of it has accumulated, so the rendered part
    * reaches at most twice that — the file crossing the budget is rendered
    * whole. Every path past it is still named, one line each. `files` is
    * unaffected, so a caller can still see every path in the change set.
    */
  def reviewChanges(since: Option[String] = None): ReviewSample

  /** Everything the next `commit` would include, in the three shapes a caller
    * describing it needs: a `--stat` summary, the paths new to the repository,
    * and the diff text [[reviewChanges]] renders.
    *
    * The paths new to the repository are the ones [[uncommittedDiff]] can't
    * report — they have no tracked history to diff against — but that a `git
    * add -A` commit would include (a nested repository as a gitlink), so
    * anything describing what is about to be committed needs them alongside the
    * diff.
    *
    * Same sampling contract as [[reviewChanges]]: one untracked sample for all
    * three shapes, tracked changes read per shape, and the same budget past
    * which an untracked file is named rather than rendered.
    */
  def pendingChanges(): PendingChanges

  /** Diff of the current branch vs `base`: the cumulative change a PR against
    * `base` would carry (three-dot, merge-base semantics — GitHub's PR view).
    *
    * Typical bases: `"origin/HEAD"`, `"main"`, `"master"`. `origin/HEAD` may
    * not be set on a freshly `git init`ed repo — see [[defaultBase]] for a
    * probe-with-fallback helper.
    *
    * Capped at `OsGitTool.MaxReadBytes` and marked where it was cut.
    */
  def diffVsBase(base: String): String

  /** Best-effort default base ref for "branch vs main" diffs. Tries
    * `origin/HEAD` first, then falls back to `origin/main` and `origin/master`.
    *
    * Throws `OrcaFlowException` when none of these refs can be resolved —
    * typically the repo has no remote configured, in which case the caller can
    * substitute a local branch name (e.g. `"main"`).
    */
  def defaultBase(): String

  /** `git show [--stat] <rev> [-- <paths>]` — a commit's message plus its diff,
    * or, under [[ShowDetail.StatOnly]], just its changed-file summary. `paths`
    * narrows the diff; empty means the whole commit. When the commit changes
    * none of `paths`, the result is a `Refused`.
    *
    * Built for agent-supplied arguments, so `rev` and `paths` are validated
    * before they reach git ([[GitRead.rev]], [[GitRead.path]]) and cannot be
    * read as flags or escape the repository. The result is capped at
    * `OsGitTool.MaxReadBytes` and says so where it was cut.
    */
  def show(
      rev: String,
      paths: List[String] = Nil,
      detail: ShowDetail = ShowDetail.Full
  ): Either[GitReadFailed, String]

  /** `git show <rev>:<path>` — one file's full contents as of `rev`. Same
    * argument validation as [[show]]. A file over `OsGitTool.MaxFileAtBytes` is
    * refused rather than cut: the size is known before the read, so naming the
    * limit beats a truncated file.
    */
  def fileAt(rev: String, path: String): Either[GitReadFailed, String]

  /** Verify the working tree is clean. If it isn't, `git stash push` with the
    * supplied message and emit a `Step` event so the user can recover the
    * changes later via `git stash pop`. Used by resumable flows that need a
    * known-clean starting state without destroying the user's work-in-progress.
    *
    * The stash-recovery hint rides on the `Step` reaching the run's dispatcher:
    * a custom `GitTool` built without the run's listener loses the hint, and
    * the user never learns to `git stash pop`.
    */
  def ensureClean(stashMessage: String)(using WorkspaceWrite): Unit

  /** The paths reported by `git status --porcelain` (modified, staged, and
    * untracked), one per entry. READ-ONLY. Used by the cleanliness policy's
    * informational notice on a fresh run that keeps a dirty tree
    * (`--skip-branch` or `--keep-changes`, ADR 0018 amendment) — the count, not
    * the parsed content, is what's shown. Emptiness is also what [[commit]] and
    * [[ensureClean]] decide on, and what the failure teardown's choice of
    * [[UntrackedFiles]] follows from, so narrowing what this reports changes
    * when they commit, when they stash, and whether untracked files are
    * deleted.
    */
  def dirtyPaths(): List[String]

  /** Force-delete a local branch (`git branch -D <name>`). Best-effort — does
    * not throw; failures are silently swallowed so callers can use this in
    * teardown without risking an error cascade. Never deletes the current
    * branch.
    */
  def deleteBranch(name: String)(using WorkspaceWrite): Unit

  /** True when `featureBranch` differs from `startBranch` outside `.orca/` —
    * the throwaway-branch check: false means the branch carries only orca
    * bookkeeping.
    */
  def branchHasChangesExcludingOrca(
      startBranch: String,
      featureBranch: String
  ): Boolean

/** `GitTool` implementation that shells out to the `git` CLI via os-lib.
  * Contract semantics are specified on the trait; this class handles the
  * subprocess plumbing.
  *
  * `events` publishes [[OrcaEvent.Step]]s for operations shown in the event log
  * (branch switches, commits, pushes). Optional — defaults to
  * `OrcaListener.noop`.
  */
private[orca] class OsGitTool(
    workDir: os.Path = os.pwd,
    events: OrcaListener = OrcaListener.noop
) extends GitTool:

  private def step(message: String): Unit =
    events.onEvent(OrcaEvent.Step(message))

  def createBranch(name: String)(using
      WorkspaceWrite
  ): Either[BranchAlreadyExists, Unit] =
    if branchExists(name) then Left(new BranchAlreadyExists(name))
    else
      val _ = git("checkout", "-b", name)
      step(s"Switched to a new branch '$name'")
      Right(())

  def checkout(
      name: String
  )(using WorkspaceWrite): Either[BranchNotFound, Unit] =
    if !branchExists(name) then Left(new BranchNotFound(name))
    else
      val _ = git("checkout", name)
      step(s"Switched to branch '$name'")
      Right(())

  // `--` so a dash-leading name is a pattern rather than a flag: without it
  // git exits 129 with its usage text, and the caller's typed `Left` never
  // happens.
  private def branchExists(name: String): Boolean =
    git("branch", "--list", "--", name).trim.nonEmpty

  def dirtyPaths(): List[String] =
    // The untracked mode is explicit because `status.showUntrackedFiles=no` in
    // a user's git config would otherwise hide untracked paths from every
    // consumer of this.
    // One porcelain line per path, except a rename ("R  old -> new"), which
    // is one line covering two paths — fine for an informational count.
    git("status", "--porcelain", "--untracked-files=normal").linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  def ensureClean(stashMessage: String)(using WorkspaceWrite): Unit =
    if dirtyPaths().nonEmpty then
      val _ = git("stash", "push", "-u", "-m", stashMessage)
      step(
        s"Working tree wasn't clean — stashed pending changes ($stashMessage). Recover with `git stash pop`."
      )

  def commit(message: String)(using
      WorkspaceWrite
  ): Either[NothingToCommit, Unit] =
    val _ = gitWithDiagnostics("add", "-A")
    if dirtyPaths().isEmpty then Left(new NothingToCommit)
    else
      val _ = gitWithDiagnostics("commit", "-m", message)
      step(s"Committed: $message")
      Right(())

  def commitOnly(path: os.Path, message: String)(using WorkspaceWrite): Unit =
    val _ = git("add", "--", path.toString)
    commitPathspec(path, message)

  def forceCommitOnly(path: os.Path, message: String)(using
      WorkspaceWrite
  ): Unit =
    val _ = git("add", "-f", path.toString)
    commitPathspec(path, message)

  /** Commit the already-staged `path` and nothing else: the commit pathspec
    * keeps anything else staged or dirty out.
    */
  private def commitPathspec(path: os.Path, message: String): Unit =
    val _ = git("commit", "-m", message, "--", path.toString)
    step(s"Committed: $message")

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
      step("Pushed to origin")
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

  // Exits 0 for an ancestor, 1 for a resolvable commit that isn't one, and 128
  // when `rev` doesn't resolve at all (a pruned object, a rebased-away commit,
  // a fresh clone) — only 0 is a usable base, so the rest collapse to false.
  def isAncestorOfHead(rev: String): Boolean =
    probeSucceeds("merge-base", "--is-ancestor", rev, "HEAD")

  /** The hash `ref` resolves to, `None` when it doesn't resolve. `--verify`
    * makes an unresolvable ref a non-zero exit rather than an echo of the ref
    * itself; `--quiet` keeps that off stderr.
    */
  private def revParse(ref: String): Option[String] =
    probe("rev-parse", "--verify", "--quiet", ref)

  def isIgnored(relPath: os.SubPath): Boolean =
    // check-ignore exits 0 when the path is ignored, 1 when it isn't, and 128
    // on error (e.g. not a git repo) — only 0 means ignored, so the error
    // cases collapse to false without special-casing.
    probeSucceeds("check-ignore", "-q", "--", relPath.toString)

  def defaultBranch(): Option[String] =
    originHead().map(_.stripPrefix("origin/"))

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
      probeSucceeds("cat-file", "-e", s"@{upstream}:./$relPath")
    catch case NonFatal(_) => false

  def discardUncommitted(untracked: UntrackedFiles)(using
      WorkspaceWrite
  ): Unit =
    val _ = git("reset", "--hard")
    untracked match
      case UntrackedFiles.Keep =>
        step("Discarded uncommitted changes (reset --hard)")
      case UntrackedFiles.Remove =>
        // `.orca` is an unanchored pattern, so it is spared at any depth —
        // including under the `:(top)` rescoping to the repository root.
        val _ = git("clean", "-fdq", "-e", orca.OrcaDir.Name, "--", ":(top)")
        step(
          "Discarded uncommitted changes and new files (reset --hard, clean " +
            "-fd excluding .orca)"
        )

  def uncommittedDiff(): String = trackedDiff("HEAD")

  private def trackedDiff(since: String): String =
    marked(gitCapped(("diff" +: since +: OsGitTool.wholeRepoExceptOrca)*))

  /** `--stat` summary of the same change set as [[uncommittedDiff]]. */
  // `--stat=<width>` widens the stat line so the name column holds a full path
  // — git's default width elides leading directories
  // (`.../orca/tools/GitTool.scala`), which defeats the point of naming files;
  // 200 clears any path this side of pathological.
  private def diffStat(): String =
    git(("diff" +: "--stat=200" +: "HEAD" +: OsGitTool.wholeRepoExceptOrca)*)

  def changedFiles(since: Option[String]): List[String] =
    allFileStats(since, untrackedPaths()).map(_.path)

  def reviewChanges(since: Option[String]): ReviewSample =
    val untracked = untrackedPaths()
    ReviewSample(
      diff = withNewFileContents(
        since.getOrElse("HEAD"),
        untracked,
        OsGitTool.MaxReadBytes
      ),
      files = allFileStats(since, untracked)
    )

  /** The whole change set as stats, over an `untracked` list the caller already
    * sampled, so a caller needing the diff alongside can share one sample.
    */
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
  private def allFileStats(
      since: Option[String],
      untracked: List[String]
  ): List[ChangedFile] =
    val args =
      "diff" +: "--numstat" +: "-z" +: "--no-relative" +:
        since.getOrElse("HEAD") +: OsGitTool.wholeRepoExceptOrca
    val tracked = OsGitTool
      .parseNumstat(git(args*))
      .map(f => f.copy(path = asWorkDirRelative(f.path)))
    (tracked ++ untracked.map(ChangedFile(_, FileChange.New)))
      .distinctBy(_.path)

  def pendingChanges(): PendingChanges =
    val untracked = untrackedPaths()
    PendingChanges(
      stat = diffStat(),
      newFiles = untracked,
      diff = withNewFileContents("HEAD", untracked, OsGitTool.MaxReadBytes)
    )

  /** The tracked diff followed by one new-file diff per untracked path,
    * stopping at the first untracked file past `budget` bytes: an agent that
    * ran a package manager or a build before anything ignored its output leaves
    * tens of thousands of untracked files, each a subprocess and a full file's
    * contents on the heap. Past the budget a path is named the same way an
    * unrenderable one is, so nothing silently disappears from the sample.
    */
  private def withNewFileContents(
      since: String,
      untracked: List[String],
      budget: Int
  ): String =
    val tracked = trackedDiff(since)

    // `budget` counts bytes, as the read cap that produces each piece does: a
    // piece cut at the cap is exactly that many bytes, so counting chars would
    // let rendering continue past a cut and put the cut marker mid-document.
    def utf8Size(piece: String): Int =
      piece.getBytes(StandardCharsets.UTF_8).length

    @scala.annotation.tailrec
    def render(
        remaining: List[String],
        size: Int,
        acc: List[String]
    ): List[String] =
      remaining match
        case Nil => acc.reverse
        case path :: rest =>
          val piece =
            if size >= budget then
              s"# skipped $path: past the $budget-byte diff budget\n"
            else untrackedFileDiff(path)
          render(rest, size + utf8Size(piece), piece :: acc)

    (tracked :: render(untracked, utf8Size(tracked), Nil)).mkString

  /** Untracked, non-`.orca/` paths anywhere in the repository, relative to
    * `workDir`. Untracked directories are recursed into, so an entry is
    * normally one file; a directory git refuses to enter — a nested repository
    * — stays one entry, with a trailing slash.
    */
  // `--untracked-files=all` is what makes that recursion happen; the default
  // mode lists only the directory. `-z` NUL-delimits records so a path
  // containing a space or newline parses unambiguously.
  private def untrackedPaths(): List[String] =
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
    probe("rev-parse", "--show-prefix").getOrElse("")

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
    * one such path does not abort the whole review. A path git exits non-zero
    * on is announced too, quoting what git said rather than naming a cause: it
    * may simply have been deleted between the untracked sample and this call (a
    * background build removing its own temp file), and a sample missing one
    * file beats an aborted flow.
    */
  private def untrackedFileDiff(relPath: String): String =
    undiffableReason(relPath) match
      case Some(reason) => s"# skipped $relPath: $reason\n"
      case None =>
        val result = gitProcCapped(
          Seq("git", "diff", "--no-index", "--", "/dev/null", relPath)
        )
        val differs = result.exitCode == 1 && result.err.isEmpty
        if result.exitCode == 0 || differs then marked(result)
        else
          val gitSaid = result.err.linesIterator
            .map(_.trim)
            .find(_.nonEmpty)
            .fold("")(line => s": $line")
          s"# skipped $relPath: git diff exited ${result.exitCode}$gitSaid\n"

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

  def diffVsBase(base: String): String =
    marked(gitCapped("diff", s"$base...HEAD"))

  def defaultBase(): String =
    originHead()
      .orElse(List("origin/main", "origin/master").find(refExists))
      .getOrElse(
        throw OrcaFlowException(
          "no default base ref found: tried origin/HEAD, origin/main, origin/master. " +
            "Either set the remote's HEAD (`git remote set-head origin -a`) or " +
            "pass an explicit base to diffVsBase."
        )
      )

  /** The remote's recorded `origin/HEAD`, in git's shortest unambiguous
    * spelling of the target — usually `origin/<branch>`, but a longer form when
    * that would be ambiguous (a local branch of the same name). `None` when
    * `origin/HEAD` is unset or git cannot be run. The single resolution point
    * behind [[defaultBase]], which passes it straight to `diff`, and
    * [[defaultBranch]], which strips the remote prefix.
    */
  private def originHead(): Option[String] =
    probe("symbolic-ref", "--short", "refs/remotes/origin/HEAD")

  private def refExists(ref: String): Boolean = revParse(ref).isDefined

  def show(
      rev: String,
      paths: List[String],
      detail: ShowDetail
  ): Either[GitReadFailed, String] = either:
    val checkedRev = GitRead.rev(rev).ok()
    val checkedPaths = paths.map(GitRead.path(_).ok())
    val pathspec =
      if checkedPaths.isEmpty then Nil else "--" +: checkedPaths
    // A diff-less render cannot be told apart from a mistyped path.
    if checkedPaths.nonEmpty && !changesAnyPath(checkedRev, pathspec).ok() then
      Left(
        new GitReadFailed.Refused(
          s"$rev shows no diff for: ${paths.mkString(", ")} — they may be " +
            "absent from that commit, present but unchanged by it, or " +
            s"hidden because $rev is a merge, for which git renders no " +
            "per-path diff by default. Retry without paths for the whole " +
            "commit, or read a file's contents at that revision."
        )
      ).ok()
    else
      val statFlag = detail match
        case ShowDetail.StatOnly => Seq("--stat")
        case ShowDetail.Full     => Nil
      // `--end-of-options` after the flags, so git cannot read `checkedRev` as
      // one however it is spelled.
      marked(
        gitRead(
          Seq("show") ++ statFlag ++
            Seq("--end-of-options", checkedRev) ++ pathspec
        ).ok()
      )

  /** Whether `checkedRev` changes any of `pathspec`. Asked of git rather than
    * read off the render: since git 2.55 a `git show <rev> -- <paths>` that
    * renders no diff still prints the commit header and message, so its output
    * is not an emptiness signal.
    *
    * Same `show` invocation as the render, so pathspec, root-commit and merge
    * semantics match it exactly; `--format=` leaves the changed-path list as
    * the only output, and git writes not one byte of it when nothing changed.
    */
  private def changesAnyPath(
      checkedRev: String,
      pathspec: Seq[String]
  ): Either[GitReadFailed, Boolean] =
    gitRead(
      Seq("show", "--name-only", "--format=", "--end-of-options", checkedRev)
        ++ pathspec
    ).map(_.out.nonEmpty)

  def fileAt(rev: String, path: String): Either[GitReadFailed, String] = either:
    val checkedRev = GitRead.rev(rev).ok()
    val checkedPath = GitRead.path(path).ok()
    val blob = s"$checkedRev:$checkedPath"
    // Ask how big it is before reading it: the whole blob lands in the heap as
    // a String, and the path is caller-chosen, so one wrong file (a vendored
    // binary, a checked-in dataset) would otherwise be an OOM rather than an
    // answer.
    val size =
      gitRead(Seq("cat-file", "-s", "--end-of-options", blob)).ok().out
    if size.trim.toLongOption.exists(_ > OsGitTool.MaxFileAtBytes) then
      Left(
        new GitReadFailed.Refused(
          s"'$path' is ${size.trim} bytes at $rev, over the " +
            s"${OsGitTool.MaxFileAtBytes}-byte limit for a whole-file read"
        )
      ).ok()
    else
      val output = gitRead(Seq("show", "--end-of-options", blob)).ok()
      // The read's own cap is the last word, whatever the size check let
      // through: a prefix returned as a file's contents is corruption the
      // caller cannot see, where a refusal is merely an answer it dislikes.
      if output.truncated then
        Left(
          new GitReadFailed.Refused(
            s"'$path' at $rev is longer than the " +
              s"${OsGitTool.MaxReadBytes}-byte read limit"
          )
        ).ok()
      else output.out

  /** Run a read-only git command, mapping a non-zero exit to
    * [[GitReadFailed.Refused]] instead of aborting the flow — an agent asking
    * for a revision that does not exist gets an answer, not a crash.
    *
    * Reports truncation rather than folding it into the text: `show` marks a
    * cut diff and carries on, while `fileAt` refuses, and only the caller knows
    * which its answer can survive.
    */
  private def gitRead(args: Seq[String]): Either[GitReadFailed, CappedResult] =
    val result = gitProcCapped("git" +: args)
    if result.exitCode != 0 then
      Left(new GitReadFailed.Refused(result.err.trim))
    else Right(result)

  def deleteBranch(name: String)(using WorkspaceWrite): Unit =
    try
      if currentBranch() != name then
        val result = gitProc(Seq("git", "branch", "-D", name))
        if result.exitCode == 0 then step(s"Deleted branch '$name'")
    catch case NonFatal(_) => ()

  def branchHasChangesExcludingOrca(
      startBranch: String,
      featureBranch: String
  ): Boolean =
    // Two-dot diff (direct) to see all changes the feature branch has vs the
    // start branch, minus the orca bookkeeping directory, so only substantive
    // code changes count. `--quiet` answers on the exit code alone: 0 when the
    // two sides match, 1 when they differ, so no diff text is produced.
    val args =
      Seq("git", "diff", "--quiet", s"$startBranch..$featureBranch") ++
        OsGitTool.wholeRepoExceptOrca
    val result = gitProc(args)
    result.exitCode match
      case 0 => false
      case 1 => true
      case _ => fail(s"git diff --quiet $startBranch..$featureBranch", result)

  /** Run a git subprocess. Every git invocation routes through here or
    * [[gitProcCapped]], so they all carry [[OsGitTool.nonInteractiveEnv]] — no
    * git (or ssh it spawns) can block the flow on an interactive credential or
    * passphrase prompt.
    */
  private def gitProc(args: Seq[String]): os.CommandResult =
    QuietProc.call(args, cwd = workDir, env = OsGitTool.nonInteractiveEnv)

  /** Trimmed stdout of a read-only git command that exited 0; `None` on a
    * non-zero exit, empty output, or a subprocess error — the shared shape of
    * this tool's best-effort reads, which answer "not known" rather than
    * aborting the flow.
    */
  private def probe(args: String*): Option[String] =
    probeStdout(args).map(_.trim).filter(_.nonEmpty)

  /** Whether a read-only git command exited 0, for the probes that answer on
    * their exit code alone and print nothing — which [[probe]] cannot tell from
    * a failure.
    */
  private def probeSucceeds(args: String*): Boolean =
    probeStdout(args).isDefined

  private def probeStdout(args: Seq[String]): Option[String] =
    try
      val result = gitProc("git" +: args)
      if result.exitCode == 0 then Some(result.out.text()) else None
    catch case NonFatal(_) => None

  /** [[gitProc]] for a command whose output size is set by what it reads rather
    * than by its arguments, keeping at most [[OsGitTool.MaxReadBytes]] of it.
    */
  private def gitProcCapped(args: Seq[String]): CappedResult =
    QuietProc.callCapped(
      args,
      maxBytes = OsGitTool.MaxReadBytes,
      cwd = workDir,
      env = OsGitTool.nonInteractiveEnv
    )

  /** Abort with a uniform `"<label> failed (exit N): <stderr>"` message for an
    * unrecoverable git failure. Callers handle the EXPECTED non-zero exits
    * (rejected push, "already exists") as `Left`s before reaching here.
    */
  private def fail(label: String, result: os.CommandResult): Nothing =
    failWith(label, result.exitCode, result.err.text().trim)

  private def failWith(label: String, exitCode: Int, stderr: String): Nothing =
    throw OrcaFlowException(s"$label failed (exit $exitCode): $stderr")

  /** Read a single git config value (`git config --get`), `None` when unset. */
  private def gitConfigGet(key: String): Option[String] =
    probe("config", "--get", key)

  private def git(args: String*): String =
    // Route through QuietProc so git's stderr ("Switched to a new branch",
    // etc.) is captured rather than leaked to the parent terminal, where it
    // would tear the renderer's status row. Branch-state changes surface in the
    // event log via the `step` calls in the public methods above.
    val result = gitProc("git" +: args)
    if result.exitCode != 0 then fail(s"git ${args.mkString(" ")}", result)
    result.out.text()

  /** [[git]] for a command whose output is bounded only by what it reads.
    * [[marked]] stays outside rather than folded in, since
    * [[untrackedFileDiff]] marks a result whose exit code it checks itself.
    */
  private def gitCapped(args: String*): CappedResult =
    val result = gitProcCapped("git" +: args)
    if result.exitCode != 0 then
      failWith(s"git ${args.mkString(" ")}", result.exitCode, result.err.trim)
    result

  private def marked(result: CappedResult): String =
    if result.truncated then result.out + OsGitTool.CutMarker else result.out

private[orca] object OsGitTool:

  /** Most stdout one capped read keeps. A heap bound, and only that: `McpHost`
    * cuts an agent's copy of the same answer to a small fraction of this, and
    * `orca.BoundedDiff` cuts a reviewer's, so the [[CutMarker]] reaches only a
    * direct [[GitTool]] caller. Applied as the output arrives, since a diff has
    * no size to ask for beforehand.
    */
  private[tools] val MaxReadBytes: Int = 2 * 1024 * 1024

  /** Appended where a capped read was cut, so a prefix is never mistaken for
    * the whole answer.
    */
  private[tools] val CutMarker: String =
    s"\n\n[cut after $MaxReadBytes bytes — narrow the request]"

  /** Largest blob [[GitTool.fileAt]] reads whole. `cat-file -s` answers the
    * size up front, which buys a refusal naming the file where [[MaxReadBytes]]
    * could only cut it. Comfortably above any source file; a request over it is
    * a wrong path, not a big one.
    *
    * Must not exceed [[MaxReadBytes]] — past that the read cuts what the size
    * check admitted. `fileAt` refuses a cut read rather than trusting this, so
    * the cost of breaking it is a refusal, not a silently truncated file.
    */
  private[tools] val MaxFileAtBytes: Int = 2 * 1024 * 1024

  /** Pathspec arguments scoping a diff to "the whole repository, minus orca's
    * bookkeeping". `:(top)` is what makes it repo-wide: a magic pathspec is
    * resolved against the process cwd, which is `workDir` — without it a tool
    * pointed at a subdirectory would silently miss every change above it. The
    * exclusion stays cwd-relative on purpose, since `.orca/` lives under
    * `workDir`, not under the repository root.
    */
  private val wholeRepoExceptOrca: Seq[String] =
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
  private def remoteHost(url: String): Option[String] =
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

  /** Snapshot of repo state captured when a commit fails. `status` is the
    * porcelain listing of what was staged at the moment of failure; `fsck`
    * reports missing/dangling objects when the failure was tree corruption.
    */
  private[tools] case class GitDiagnostics(status: String, fsck: String)

  /** Format a git subprocess failure into the message used by the thrown
    * exception. `cmd` is the argv after `git ` (e.g. `commit -m seed` or `add
    * -A`). Sectioned so the original stderr stays at the top and the
    * diagnostics follow on their own lines.
    *
    * No `stripMargin`: `stderr` carries whatever git and any hook it ran wrote,
    * so a line of it can start with `|`, which a margin block would eat. The
    * two diagnostic blocks only escape that because each of their lines is
    * indented first.
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
    s"git $cmd failed: ${stderr.trim}\n\n" +
      s"git status --porcelain:\n$statusBlock\n\n" +
      s"git fsck --no-progress:\n$fsckBlock"
