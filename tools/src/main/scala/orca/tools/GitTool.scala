package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.gitref.{CommitHash, Head}

/** How much of a commit [[GitTool.show]] renders. */
enum ShowDetail:
  /** Message plus the full diff. */
  case Full

  /** Message plus `--stat`: which files changed and by how much, no hunks. */
  case StatOnly

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
  * names every path in it, including the ones `diff` cannot show. `sections`
  * maps a path to its own whole part of `diff`. A file has no entry when its
  * part could not be paired with its stats, was cut by the read cap, or is only
  * a line naming the file (`# skipped …`).
  */
case class ReviewSample(
    diff: String,
    files: List[ChangedFile],
    sections: Map[String, String]
)

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

/** [[GitTool.defaultBase]] found no ref to diff against. `cause` names the refs
  * tried, for a caller wording the remedy itself.
  */
final class NoDefaultBase
    extends OrcaFlowException(
      s"no default base ref found: ${NoDefaultBase.Cause}. Either set the " +
        "remote's HEAD (`git remote set-head origin -a`) or pass an explicit " +
        "base to diffVsBase."
    ):
  def cause: String = NoDefaultBase.Cause

object NoDefaultBase:
  private val Cause = "tried origin/HEAD, origin/main, origin/master"

/** Git adapter usable from flow scripts — the handle behind the `git` accessor.
  * Reads the working repository and pushes; branches and commits belong to the
  * runtime ([[RuntimeGit]]).
  */
trait GitTool:

  /** Push the current branch, setting upstream on first push. Returns
    * `Left(PushFailure)` when the remote rejected the push for a reason the
    * caller might recover from — see [[PushFailure]] for the two shapes and
    * their differing recovery contracts. Other failures (auth, network) throw.
    */
  def push()(using WorkspaceWrite): Either[PushFailure, Unit]

  /** The branch HEAD is on, or the commit it is detached at. READ-ONLY. Throws
    * `OrcaFlowException` when HEAD names no commit, or is on a branch whose
    * name [[orca.gitref.BranchName.parse]] refuses.
    */
  def head(): Head

  /** The commit HEAD resolves to, as a full hash. READ-ONLY. Best-effort:
    * `None` when HEAD names no commit (a repository with no history yet) or the
    * probe cannot answer.
    */
  def headCommit(): Option[CommitHash]

  /** True when `rev` both resolves in this repository and is an ancestor of
    * HEAD — i.e. usable as a diff base for "everything since `rev`". READ-ONLY.
    * Best-effort: `false` whenever the probe cannot answer, so an unknown
    * answer reads as "not a usable base" rather than producing a wrong range.
    */
  def isAncestorOfHead(commit: CommitHash): Boolean

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
  def changedFiles(since: Option[CommitHash] = None): List[String]

  /** The change set a reviewer should see, as diff text, as the list of paths
    * in it, and as each file's own part of the diff — what a caller rendering a
    * bounded diff needs, since it cuts between files and has to name the ones
    * it leaves out (see `orca.BoundedDiff`).
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
    * The UNTRACKED set is sampled once for every projection, so a file created
    * mid-call cannot land in one and not the other. Tracked changes come from
    * one `git diff` call printing both stats and patch — unless the stats alone
    * fill the read cap, when the two are read separately.
    *
    * The diff text is bounded: untracked files stop being rendered once
    * `OsGitTool.MaxReadBytes` of it has accumulated, so the rendered part
    * reaches at most twice that — the file crossing the budget is rendered
    * whole. Every path past it is still named, one line each. `files` is
    * unaffected, so a caller can still see every path in the change set.
    *
    * A tracked file's section is paired with its stats by position in that
    * call's output, so a rename, a quoted path or a `workDir` below the
    * repository root is keyed like any other file.
    */
  def reviewChanges(since: Option[CommitHash] = None): ReviewSample

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
    * `Left(NoDefaultBase)` when none of these refs resolves — typically the
    * repo has no remote configured, in which case the caller can substitute a
    * local branch name (e.g. `"main"`).
    */
  def defaultBase(): Either[NoDefaultBase, String]

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
