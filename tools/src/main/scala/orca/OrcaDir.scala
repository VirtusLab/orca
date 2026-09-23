package orca

import ox.tap

/** Layout of the `.orca/` directory (ADR 0019): committed project metadata
  * lives at the root, ephemeral state lives under `cache/`, which self-ignores
  * via its own `.gitignore` and carries a `CACHEDIR.TAG` so backup tools skip
  * it. All `.orca` creation routes through this object, so the exclusion
  * markers are always in place before anything is written into the cache.
  *
  * `worktrees/` is the one root entry that is not committed: it self-ignores
  * like the cache but is not disposable like it — see [[ensureWorktrees]].
  */
private[orca] object OrcaDir:
  private val gitignoreContents = "# Automatically created by orca.\n*\n"

  // The Signature line is fixed by the CACHEDIR.TAG spec (bradfitz.com/cachedir);
  // tools ignore the tag without it.
  private val cachedirTagContents =
    "Signature: 8a477f597d28d172789f06886806bc55\n" +
      "# This file marks .orca/cache as a cache directory, so backup tools skip it.\n"

  /** The directory's name. Every path built here and every git-side exclusion
    * derives from it, so the filesystem side and the git side can't drift into
    * naming different directories.
    */
  val Name: String = ".orca"

  /** Git pathspec excluding everything under the directory, resolved against
    * the process cwd — which for `OsGitTool` is its `workDir`, the same base
    * [[rootPath]] uses. It matches the directory's contents, which is all a
    * diff ever names: git reports files, never the directory itself.
    */
  val ExcludePathspec: String = s":(exclude)$Name/*"

  /** `<workDir>/.orca` — committed project metadata lives at this root.
    * Passive; callers that write through it guard it first (e.g. the symlink
    * check in `FlowLifecycle.readSettings`) or go through [[ensureRoot]].
    */
  def rootPath(workDir: os.Path): os.Path = workDir / Name

  /** Suffix of a progress log's file name, after the [[RunKey]]. */
  val ProgressLogSuffix: String = ".progress.json"

  /** Suffix of an attempt manifest's file name, after the [[AttemptId]]. */
  val ManifestSuffix: String = ".manifest.json"

  /** Suffix of a cost log's file name, after the [[AttemptId]]. `.jsonl`, not
    * `.json`: the manifest listing selects by suffix, and a cost log must never
    * reach it as a manifest that fails to decode.
    */
  val CostLogSuffix: String = ".cost.jsonl"

  private val TraceLogStem = ".trace"

  /** Suffix of a trace log's file name, after the [[AttemptId]]. */
  val TraceLogSuffix: String = s"$TraceLogStem.log"

  /** The index of a trace log's single rolled-over part: `OrcaLog` keeps only
    * this one, and [[attemptIdOf]] recognises only this one.
    */
  val TraceLogRollIndex: Int = 1

  /** Suffix of a trace log's rolled-over earlier part, after the [[AttemptId]]:
    * [[traceLogRollPattern]] with [[TraceLogRollIndex]].
    */
  val RolledTraceLogSuffix: String = s"$TraceLogStem.$TraceLogRollIndex.log"

  /** Repo-relative form of the settings path, for git probes that take a path
    * relative to the repository root.
    */
  val settingsSubPath: os.SubPath = os.sub / Name / "settings.properties"

  /** `<workDir>/.orca/settings.properties` (ADR 0019). */
  def settingsPath(workDir: os.Path): os.Path = workDir / settingsSubPath

  /** `<workDir>/.orca/runs`, passively — the committed directory of the per-run
    * progress logs, for the read side (`ProgressScan`'s listing), which must
    * not create `.orca` as a side effect. [[ensureRuns]] is the write-side
    * counterpart.
    */
  def runsPath(workDir: os.Path): os.Path = rootPath(workDir) / "runs"

  /** Idempotently ensure `.orca/runs/` exists and return it. Committed like the
    * rest of `.orca`'s root, so no exclusion markers: the progress logs in it
    * ride the feature branch.
    */
  def ensureRuns(workDir: os.Path): os.Path =
    ensureDir(workDir, runsPath(workDir))

  /** `<workDir>/.orca/runs/<key>.progress.json` — the progress log of the run
    * keyed `key`.
    */
  def progressPath(workDir: os.Path, key: RunKey): os.Path =
    runsPath(workDir) / s"${key.value}$ProgressLogSuffix"

  /** Whether `file` is named like a progress log under [[runsPath]]. */
  def isProgressLog(file: os.Path): Boolean =
    file.last.endsWith(ProgressLogSuffix)

  /** Idempotently ensure `.orca/` exists and return it. */
  def ensureRoot(workDir: os.Path): os.Path =
    ensureDir(workDir, rootPath(workDir))

  /** `<workDir>/.orca/cache`, passively — for callers that only need the path
    * (a file to delete, an argument to hand a subprocess) and must not create
    * anything. [[ensureCache]] is the write-side counterpart.
    */
  def cachePath(workDir: os.Path): os.Path = rootPath(workDir) / "cache"

  /** `<workDir>/.orca/cache/runs/<key>.sessions.json` — the durable-session
    * records of the run keyed `key`, paired with the progress log at
    * [[progressPath]] under the same key. Untracked, so the records survive the
    * failure teardown's `git reset --hard` that the committed log cannot.
    */
  def sessionRecordsPath(workDir: os.Path, key: RunKey): os.Path =
    cacheRunsPath(workDir) / s"${key.value}.sessions.json"

  /** `<workDir>/.orca/cache/flow.lock` — held by the run in `workDir`. */
  def flowLockPath(workDir: os.Path): os.Path = cachePath(workDir) / "flow.lock"

  /** `<mainCheckout>/.orca/cache/worktree-<key>.lock` — held while the
    * `--worktree` run keyed `key` finds or creates its worktree.
    */
  def worktreeLockPath(mainCheckout: os.Path, key: RunKey): os.Path =
    cachePath(mainCheckout) / s"worktree-${key.value}.lock"

  private def cacheRunsPath(workDir: os.Path): os.Path =
    cachePath(workDir) / "runs"

  /** Idempotently ensure `<workDir>/.orca/cache/runs/` exists and return it,
    * for the write side of [[sessionRecordsPath]].
    */
  def ensureCacheRuns(workDir: os.Path): os.Path =
    ensureCacheDir(workDir, cacheRunsPath(workDir))

  /** Idempotently ensure `.orca/cache/` exists, writing its self-ignoring
    * `.gitignore` and `CACHEDIR.TAG` before returning so nothing lands in the
    * dir before the exclusion is in place. Markers are written only when
    * absent, so repeated calls do not churn mtimes.
    */
  def ensureCache(workDir: os.Path): os.Path =
    ensureDir(workDir, cachePath(workDir)).tap: cache =>
      writeIfAbsent(cache / ".gitignore", gitignoreContents)
      writeIfAbsent(cache / "CACHEDIR.TAG", cachedirTagContents)

  /** `<workDir>/.orca/cache/attempts`, passively — the read-side counterpart to
    * [[ensureAttempts]] for the shell's manifest listing (ADR 0021 §8), which
    * must not create `.orca` as a side effect of reading it.
    */
  def attemptsPath(workDir: os.Path): os.Path = cachePath(workDir) / "attempts"

  /** Idempotently ensure `<workDir>/.orca/cache/attempts/` exists and return
    * it. Holds the manifests and cost logs `AttemptManifestWriter` writes (ADR
    * 0021 §8), and the trace logs `OrcaLog` writes. Created at every attempt's
    * start: the shell ranks worktrees by this directory's mtime.
    */
  def ensureAttempts(workDir: os.Path): os.Path =
    ensureCacheDir(workDir, attemptsPath(workDir))

  /** `<workDir>/.orca/cache/attempts/<id>.manifest.json` — the manifest of
    * attempt `id`.
    */
  def manifestPath(workDir: os.Path, id: AttemptId): os.Path =
    attemptsPath(workDir) / s"${id.value}$ManifestSuffix"

  /** Whether `file` is named like an attempt manifest under [[attemptsPath]].
    */
  def isManifest(file: os.Path): Boolean = file.last.endsWith(ManifestSuffix)

  /** `<workDir>/.orca/cache/attempts/<id>.cost.jsonl` — the cost log of attempt
    * `id`.
    */
  def costLogPath(workDir: os.Path, id: AttemptId): os.Path =
    attemptsPath(workDir) / s"${id.value}$CostLogSuffix"

  /** `<workDir>/.orca/cache/attempts/<id>.trace.log` — the trace log of attempt
    * `id`; its rolled-over part ends in [[RolledTraceLogSuffix]].
    */
  def traceLogPath(workDir: os.Path, id: AttemptId): os.Path =
    attemptsPath(workDir) / s"${id.value}$TraceLogSuffix"

  /** Logback file-name pattern for the rolled-over parts of attempt `id`'s
    * trace log, `%i` standing for the part's index.
    */
  def traceLogRollPattern(workDir: os.Path, id: AttemptId): String =
    (attemptsPath(workDir) / s"${id.value}$TraceLogStem.%i.log").toString

  /** The attempt a file under [[attemptsPath]] belongs to: the [[AttemptId]]
    * before a manifest, cost-log or trace-log suffix. `None` for anything else
    * there, including an in-flight temp file.
    */
  def attemptIdOf(file: os.Path): Option[AttemptId] =
    List(ManifestSuffix, CostLogSuffix, TraceLogSuffix, RolledTraceLogSuffix)
      .collectFirst:
        case suffix if file.last.endsWith(suffix) =>
          file.last.dropRight(suffix.length)
      .flatMap(AttemptId.parse)

  /** `<workDir>/.orca/cache/pi-sessions`, passively. Holds one child directory
    * per orca session id, each pi's own `--session-dir` transcript store;
    * living in the cache is what lets a pi chat be resumed after the run that
    * created it. Passive like [[attemptsPath]], for the read side (pi's
    * existence probe) — only [[ensurePiSessions]] creates.
    */
  def piSessionsPath(workDir: os.Path): os.Path =
    cachePath(workDir) / "pi-sessions"

  /** Idempotently ensure `.orca/cache/pi-sessions/` exists and return it, for
    * the write side (spawning pi at a session dir under it).
    */
  def ensurePiSessions(workDir: os.Path): os.Path =
    ensureCacheDir(workDir, piSessionsPath(workDir))

  /** `<workDir>/.orca/worktrees`, passively — a run's worktree is derived from
    * this path before anything decides whether to create it.
    */
  def worktreesPath(workDir: os.Path): os.Path = rootPath(workDir) / "worktrees"

  /** Idempotently ensure `.orca/worktrees/` exists, writing its self-ignoring
    * `.gitignore` before returning: without the marker, `git add -A` in this
    * checkout stages a worktree under it as an embedded git repository, so the
    * marker must be in place before the first worktree is created.
    *
    * Unlike [[ensureCache]] there is deliberately no `CACHEDIR.TAG` — the tag
    * tells backup tools to skip the directory, and a worktree holds unmerged
    * work plus the only copy of the progress log that resumes a failed run.
    */
  def ensureWorktrees(workDir: os.Path): os.Path =
    ensureDir(workDir, worktreesPath(workDir)).tap: worktrees =>
      writeIfAbsent(worktrees / ".gitignore", gitignoreContents)

  /** `<workDir>/.orca/flows` — project-tier flow scripts (ADR 0021 §5),
    * committed like the rest of `.orca`'s root.
    */
  def flowsPath(workDir: os.Path): os.Path = rootPath(workDir) / "flows"

  /** `<workDir>/.orca/reviewers` — project-tier reviewer prompts, committed
    * like the rest of `.orca`'s root.
    */
  def reviewersPath(workDir: os.Path): os.Path = rootPath(workDir) / "reviewers"

  /** Idempotently ensure `.orca/flows/` exists and return it. */
  def ensureFlows(workDir: os.Path): os.Path =
    ensureDir(workDir, flowsPath(workDir))

  /** Idempotently create `dir` under `.orca`, refusing to write through a
    * symlinked component.
    */
  private def ensureDir(workDir: os.Path, dir: os.Path): os.Path =
    abortIfOrcaComponentSymlink(workDir, dir)
    dir.tap(os.makeDir.all(_))

  /** [[ensureDir]] for a directory under `.orca/cache`, with the cache's
    * exclusion markers in place first.
    */
  private def ensureCacheDir(workDir: os.Path, dir: os.Path): os.Path =
    val _ = ensureCache(workDir)
    ensureDir(workDir, dir)

  /** Read-only counterpart to [[abortIfOrcaComponentSymlink]], for callers that
    * only need to verify before reading (e.g. flow discovery) and must not
    * create `.orca` as a side effect. A no-op when `.orca` doesn't exist yet —
    * there is nothing to guard, and discovery already tolerates a missing tier
    * directory.
    */
  private[orca] def assertNoOrcaSymlinks(workDir: os.Path, dir: os.Path): Unit =
    if os.exists(rootPath(workDir)) then
      abortIfOrcaComponentSymlink(workDir, dir)

  /** Refuse if `.orca` — or any orca-created directory from it down to `dir`
    * (inclusive) — is a symlink, before any `os.makeDir.all`/write through it.
    * A committed symlink at any component (git mode 120000) would redirect
    * orca's writes to the link's target, outside the working tree. Each segment
    * is checked on its own because `os.isLink` (lstat, no-follow) inspects only
    * the final component, so a symlinked ancestor would be invisible to a
    * leaf-only check; the no-follow also catches a dangling final link.
    *
    * Accepted residual (TOCTOU): a purely LOCAL race could swap a plain
    * component for a symlink between the check and the write. Out of scope
    * under the committed-repo-symlink threat model, so left open deliberately.
    */
  private def abortIfOrcaComponentSymlink(
      workDir: os.Path,
      dir: os.Path
  ): Unit =
    val r = rootPath(workDir)
    // Every path segment from `.orca` down to `dir`, inclusive, walking up from
    // `dir` so the check order is root-first.
    @scala.annotation.tailrec
    def componentsUpTo(p: os.Path, acc: List[os.Path]): List[os.Path] =
      if p == r then r :: acc
      else componentsUpTo(p / os.up, p :: acc)
    componentsUpTo(dir, Nil).foreach: component =>
      if os.isLink(component) then
        throw new OrcaFlowException(
          s"$component is a symlink — refusing to create or write through it " +
            "(a committed symlink at or below .orca could redirect orca's " +
            "writes outside the working tree)"
        )

  // On first-ever cache creation two processes can both see the file absent
  // before the flow lock exists to serialize them; the loser's `os.write`
  // (CREATE_NEW) throws. Both writers carry identical contents, so losing the
  // race is harmless.
  private def writeIfAbsent(path: os.Path, contents: String): Unit =
    if !os.exists(path) then
      try os.write(path, contents)
      catch
        case _: java.nio.file.FileAlreadyExistsException =>
          () // lost the create race; content is identical
