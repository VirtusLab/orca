package orca

import ox.tap

/** Layout of the `.orca/` directory (ADR 0019): committed project metadata
  * lives at the root, ephemeral state lives under `cache/`, which self-ignores
  * via its own `.gitignore` and carries a `CACHEDIR.TAG` so backup tools skip
  * it. All `.orca` creation routes through this object, so the exclusion
  * markers are always in place before anything is written into the cache.
  */
private[orca] object OrcaDir:
  private val gitignoreContents = "# Automatically created by orca.\n*\n"

  // The Signature line is fixed by the CACHEDIR.TAG spec (bradfitz.com/cachedir);
  // tools ignore the tag without it.
  private val cachedirTagContents =
    "Signature: 8a477f597d28d172789f06886806bc55\n" +
      "# This file marks .orca/cache as a cache directory, so backup tools skip it.\n"

  /** `<workDir>/.orca` — committed project metadata lives at this root. */
  private def root(workDir: os.Path): os.Path = workDir / ".orca"

  /** `<workDir>/.orca` — the committed-metadata root, for callers that guard it
    * (e.g. the symlink check in `FlowLifecycle.readSettings`) before writing
    * through it.
    */
  def rootPath(workDir: os.Path): os.Path = root(workDir)

  /** Repo-relative form of the settings path, for git probes that take a path
    * relative to the repository root.
    */
  val settingsSubPath: os.SubPath = os.sub / ".orca" / "settings.properties"

  /** `<workDir>/.orca/settings.properties` (ADR 0019). */
  def settingsPath(workDir: os.Path): os.Path = workDir / settingsSubPath

  /** `<workDir>/.orca/progress-<promptHash>.json` — a flow run's progress log.
    */
  def progressPath(workDir: os.Path, promptHash: String): os.Path =
    root(workDir) / s"progress-$promptHash.json"

  /** Idempotently ensure `.orca/` exists and return it. */
  def ensureRoot(workDir: os.Path): os.Path =
    val r = root(workDir)
    abortIfOrcaComponentSymlink(workDir, r)
    r.tap(os.makeDir.all(_))

  /** Idempotently ensure `.orca/cache/` exists, writing its self-ignoring
    * `.gitignore` and `CACHEDIR.TAG` before returning so nothing lands in the
    * dir before the exclusion is in place. Markers are written only when
    * absent, so repeated calls do not churn mtimes.
    */
  def ensureCache(workDir: os.Path): os.Path =
    val cache = root(workDir) / "cache"
    abortIfOrcaComponentSymlink(workDir, cache)
    os.makeDir.all(cache)
    writeIfAbsent(cache / ".gitignore", gitignoreContents)
    writeIfAbsent(cache / "CACHEDIR.TAG", cachedirTagContents)
    cache

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
    val r = root(workDir)
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
