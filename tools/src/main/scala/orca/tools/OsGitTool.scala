package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.gitref.{BranchName, CommitHash, Head}
import orca.subprocess.{CappedResult, QuietProc}
import ox.either
import ox.either.ok

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** `RuntimeGit` implementation that shells out to the `git` CLI via os-lib.
  * Contract semantics are specified on the trait; this class handles the
  * subprocess plumbing.
  *
  * `events` publishes [[OrcaEvent.Step]]s for operations shown in the event log
  * (branch switches, commits, pushes), and [[OrcaEvent.Bookkeeping]] for the
  * pathspec commits of orca's own files. Optional — defaults to
  * `OrcaListener.noop`.
  */
private[orca] class OsGitTool(
    workDir: os.Path = os.pwd,
    events: OrcaListener = OrcaListener.noop
) extends RuntimeGit:

  private def step(message: String): Unit =
    events.onEvent(OrcaEvent.Step(message))

  def createBranch(name: BranchName)(using
      ws: WorkspaceWrite
  ): Either[BranchAlreadyExists, Unit] =
    ws.check("git.createBranch")
    if branchExists(name) then Left(new BranchAlreadyExists(name))
    else
      val _ = git("checkout", "-b", name.value)
      step(s"Switched to a new branch '${name.value}'")
      Right(())

  def checkout(
      name: BranchName
  )(using ws: WorkspaceWrite): Either[BranchNotFound, Unit] =
    ws.check("git.checkout")
    if !branchExists(name) then Left(new BranchNotFound(name))
    else
      // `--` so a file sharing the branch's name can't make it ambiguous.
      val _ = git("checkout", name.value, "--")
      step(s"Switched to branch '${name.value}'")
      Right(())

  def checkoutDetached(at: CommitHash)(using ws: WorkspaceWrite): Unit =
    ws.check("git.checkoutDetached")
    val _ = git("checkout", "--detach", at.value)
    step(s"Switched to detached HEAD at ${at.short}")

  def branchExists(name: BranchName): Boolean =
    git("branch", "--list", name.value).trim.nonEmpty

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

  def ensureClean(stashMessage: String)(using ws: WorkspaceWrite): Unit =
    ws.check("git.ensureClean")
    if dirtyPaths().nonEmpty then
      val _ = git("stash", "push", "-u", "-m", stashMessage)
      step(
        s"Working tree wasn't clean — stashed pending changes ($stashMessage). Recover with `git stash pop`."
      )

  def commit(message: String)(using
      ws: WorkspaceWrite
  ): Either[NothingToCommit, Unit] =
    ws.check("git.commit")
    val _ = gitWithDiagnostics("add", "-A")
    if dirtyPaths().isEmpty then Left(new NothingToCommit)
    else
      val _ = gitWithDiagnostics("commit", "-m", message)
      step(s"Committed: $message")
      Right(())

  def commitOnly(path: os.Path, message: String)(using
      ws: WorkspaceWrite
  ): Unit =
    ws.check("git.commitOnly")
    val _ = git("add", "--", path.toString)
    commitPathspec(path, message)

  def forceCommitOnly(path: os.Path, message: String)(using
      ws: WorkspaceWrite
  ): Unit =
    ws.check("git.forceCommitOnly")
    val _ = git("add", "-f", path.toString)
    commitPathspec(path, message)

  /** Commit the already-staged `path` and nothing else: the commit pathspec
    * keeps anything else staged or dirty out.
    */
  private def commitPathspec(path: os.Path, message: String): Unit =
    val _ = git("commit", "-m", message, "--", path.toString)
    events.onEvent(OrcaEvent.Bookkeeping(s"Committed: $message"))

  def forceAdd(path: os.Path)(using ws: WorkspaceWrite): Unit =
    ws.check("git.forceAdd")
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

  def push()(using ws: WorkspaceWrite): Either[PushFailure, Unit] =
    ws.check("git.push")
    // Uses `gitProc` (returns the result) rather than `git` (throws on
    // non-zero) so failure stderr can be inspected to split the recoverable
    // cases (non-fast-forward, remote-declined) from auth/network errors.
    // `pushArgs` appends a last-resort credential helper (see its doc).
    val pushUrl = probe("remote", "get-url", "--push", "origin")
    val result = gitProc(OsGitTool.pushArgs(pushUrl))
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

  // `symbolic-ref --quiet` exits 1 exactly when HEAD is detached.
  def head(): Head =
    val result = gitProc(Seq("git", "symbolic-ref", "--quiet", "HEAD"))
    result.exitCode match
      case 0 => Head.OnBranch(OsGitTool.branchOf(result.out.text().trim))
      case 1 =>
        Head.Detached(
          headCommit().getOrElse(
            throw OrcaFlowException("HEAD does not resolve to a commit")
          )
        )
      case _ => fail("git symbolic-ref HEAD", result)

  def headCommit(): Option[CommitHash] =
    revParse("HEAD").flatMap(CommitHash.from)

  // Exits 0 for an ancestor, 1 for a resolvable commit that isn't one, and 128
  // when `commit` doesn't resolve at all (a pruned object, a rebased-away
  // commit, a fresh clone) — only 0 is a usable base, so the rest collapse to
  // false.
  def isAncestorOfHead(commit: CommitHash): Boolean =
    probeSucceeds("merge-base", "--is-ancestor", commit.value, "HEAD")

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

  // `--quiet` makes an unset `origin/HEAD` exit 1 without a message, so any
  // other non-zero exit is git failing. The full ref rather than `--short`,
  // which prints `remotes/origin/<x>` when a local branch `origin/<x>` exists.
  def defaultBranch(): Option[String] =
    val result =
      gitProc(Seq("git", "symbolic-ref", "--quiet", OsGitTool.OriginHeadRef))
    result.exitCode match
      case 0 =>
        Some(result.out.text().trim.stripPrefix(OsGitTool.OriginRefsPrefix))
      case 1 => None
      // Not `fail`: this refusal stops a run at preflight, so it names the fix.
      case exit =>
        throw OrcaFlowException(
          s"cannot read the default branch from origin/HEAD (exit $exit: " +
            s"${result.err.text().trim}) — fix the repository, or reset " +
            "origin/HEAD with `git remote set-head origin -a`, and re-run"
        )

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
      ws: WorkspaceWrite
  ): Unit =
    ws.check("git.discardUncommitted")
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

  def snapshotUncommitted()(using
      ws: WorkspaceWrite
  ): Either[SnapshotFailed, Option[UncommittedSnapshot]] =
    ws.check("git.snapshotUncommitted")
    val result = gitProc(Seq("git", "stash", "create"))
    val out = result.out.text().trim
    if result.exitCode != 0 then
      Left(SnapshotFailed(s"git stash create: ${result.err.text().trim}"))
    // `stash create` prints nothing when no tracked file differs from HEAD.
    else if out.isEmpty then Right(None)
    else
      (CommitHash.from(out), headCommit()) match
        case (Some(commit), Some(base)) =>
          Right(Some(UncommittedSnapshot(commit, base)))
        case (None, _) =>
          Left(SnapshotFailed(s"git stash create printed '$out', not a hash"))
        case (_, None) =>
          Left(SnapshotFailed("HEAD does not resolve to a commit"))

  def restoreSnapshot(snapshot: UncommittedSnapshot)(using
      ws: WorkspaceWrite
  ): Either[SnapshotFailed, Unit] =
    ws.check("git.restoreSnapshot")
    val result =
      gitProc(Seq("git", "stash", "apply", "--index", snapshot.commit.value))
    if result.exitCode == 0 then Right(())
    else Left(SnapshotFailed(s"git stash apply: ${result.err.text().trim}"))

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

  def changedFiles(since: Option[CommitHash]): List[String] =
    allFileStats(since, untrackedPaths()).map(_.path)

  def trackedChangedFiles(since: CommitHash): List[String] =
    allFileStats(Some(since), Nil).map(_.path)

  def reviewChanges(since: Option[CommitHash]): ReviewSample =
    val untracked = untrackedPaths()
    ReviewSample(
      diff = withNewFileContents(
        since.fold("HEAD")(_.value),
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
      since: Option[CommitHash],
      untracked: List[String]
  ): List[ChangedFile] =
    val args =
      "diff" +: "--numstat" +: "-z" +: "--no-relative" +:
        since.fold("HEAD")(_.value) +: OsGitTool.wholeRepoExceptOrca
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

  // `--end-of-options` so a `base` spelled like a flag is refused as a
  // revision rather than obeyed (`--output=<path>` would write a file).
  def diffVsBase(base: String): String =
    marked(gitCapped("diff", "--end-of-options", s"$base...HEAD"))

  // `--short` gives git's shortest unambiguous spelling of the target, which
  // `diff` accepts as is. A failed read falls through to the fallbacks: unlike
  // `defaultBranch`, which protects a branch, a wrong diff base is safe.
  def defaultBase(): Either[NoDefaultBase, String] =
    probe("symbolic-ref", "--short", OsGitTool.OriginHeadRef)
      .orElse(List("origin/main", "origin/master").find(refExists))
      .toRight(new NoDefaultBase)

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

  def deleteBranch(name: BranchName)(using ws: WorkspaceWrite): Unit =
    ws.check("git.deleteBranch")
    try
      if head() != Head.OnBranch(name) then
        val result = gitProc(Seq("git", "branch", "-D", name.value))
        if result.exitCode == 0 then step(s"Deleted branch '${name.value}'")
    catch case NonFatal(_) => ()

  def branchHasChangesExcludingOrca(
      since: CommitHash,
      featureBranch: BranchName
  ): Boolean =
    // Two-dot diff (direct) to see all changes the feature branch has vs
    // `since`, minus the orca bookkeeping directory, so only substantive code
    // changes count. `--quiet` answers on the exit code alone: 0 when the two
    // sides match, 1 when they differ, so no diff text is produced.
    val range = s"${since.value}..${featureBranch.value}"
    val result = gitProc(
      Seq("git", "diff", "--quiet", range) ++ OsGitTool.wholeRepoExceptOrca
    )
    result.exitCode match
      case 0 => false
      case 1 => true
      case _ => fail(s"git diff --quiet $range", result)

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

  private val OriginRefsPrefix = "refs/remotes/origin/"

  /** The remote's recorded default branch. */
  private val OriginHeadRef = s"${OriginRefsPrefix}HEAD"

  /** The branch a `git symbolic-ref HEAD` answer names. The full ref is read
    * rather than `--short`'s, which prints `heads/<name>` when a tag shares the
    * name.
    */
  private[tools] def branchOf(symbolicRef: String): BranchName =
    BranchName
      .fromRef(symbolicRef)
      .getOrElse(
        throw OrcaFlowException(
          s"HEAD is on '$symbolicRef', which orca cannot use as a branch — " +
            "check out a local branch, or rename this one " +
            "(`git branch -m <new-name>`), and re-run"
        )
      )

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

  /** Host of a git remote URL, for both `scp`-like SSH (`[user@]host:path`) and
    * URL forms (`scheme://[user@]host[:port]/path`). `None` for local paths or
    * anything without a recognisable host, including one outside [[HostName]].
    */
  private[tools] def remoteHost(url: String): Option[String] =
    val scpLike = s"""^[^@/]+@($HostName):.*""".r
    val urlLike =
      s"""^[a-zA-Z][a-zA-Z0-9+.\\-]*://(?:[^@/]+@)?($HostName)(?:[:/].*)?""".r
    // Userless `host:path`, as git reads it: no `/` before the first `:`, and
    // two-plus characters so a Windows drive (`c:/repos`) is a path, not a host.
    val userlessScp = s"""^($HostName):(?!//).*""".r
    url.trim match
      case scpLike(host)                         => Some(host)
      case urlLike(host)                         => Some(host)
      case userlessScp(host) if host.length >= 2 => Some(host)
      case _                                     => None

  /** The `git push` argv. For an `https` push URL it appends a credential
    * helper scoped to that URL's host, so the push authenticates even when git
    * has no helper configured. Appended after any config-file helpers, so a
    * user's existing credential setup still wins. See [[credentialHelper]] for
    * what answers. A host on a non-default port gets no answer: git matches the
    * helper's URL by port too.
    */
  private[tools] def pushArgs(pushUrl: Option[String]): Seq[String] =
    val credential = pushUrl
      .filter(_.startsWith("https://"))
      .flatMap(remoteHost)
      .fold(Nil)(host =>
        Seq("-c", s"credential.https://$host.helper=${credentialHelper(host)}")
      )
    (Seq("git") ++ credential) ++ Seq("push", "-u", "origin", "HEAD")

  /** Shell credential helper for `host`. On github.com it echoes
    * `$GH_TOKEN`/`$GITHUB_TOKEN` when one is set (`x-access-token` is GitHub's
    * conventional username for token auth), read at helper runtime so it stays
    * out of argv and logs, and otherwise asks gh. On any other host it asks gh
    * with gh's environment tokens unset: gh hands `$GH_ENTERPRISE_TOKEN` (and a
    * Codespace's `$GITHUB_TOKEN`) to every host it does not know to be
    * github.com, so only a stored `gh auth login` for that host answers.
    */
  private def credentialHelper(host: String): String =
    if host == "github.com" then
      "!f() { test \"$1\" = get || return 0; " +
        "t=\"${GH_TOKEN:-$GITHUB_TOKEN}\"; " +
        "if [ -n \"$t\" ]; then " +
        "printf 'username=x-access-token\\npassword=%s\\n' \"$t\"; " +
        "else gh auth git-credential get; fi; }; f"
    else
      "!env -u GH_TOKEN -u GITHUB_TOKEN -u GH_ENTERPRISE_TOKEN " +
        "-u GITHUB_ENTERPRISE_TOKEN gh auth git-credential"

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
