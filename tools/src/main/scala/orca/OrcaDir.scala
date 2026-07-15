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
    root(workDir).tap(os.makeDir.all(_))

  /** Idempotently ensure `.orca/cache/` exists — writing its self-ignoring
    * `.gitignore` and `CACHEDIR.TAG` before returning, so nothing can land in
    * the dir before the exclusion is in place — and return it. The marker files
    * are written only when absent, so repeated calls do not churn mtimes.
    */
  def ensureCache(workDir: os.Path): os.Path =
    val cache = root(workDir) / "cache"
    os.makeDir.all(cache)
    writeIfAbsent(cache / ".gitignore", gitignoreContents)
    writeIfAbsent(cache / "CACHEDIR.TAG", cachedirTagContents)
    cache

  // Check-then-write races on the first-ever cache creation: two processes can
  // both see the file absent before the flow lock exists to serialize them, so
  // the loser's `os.write` (CREATE_NEW) throws. Both writers carry identical
  // contents, so losing the create race is harmless.
  private def writeIfAbsent(path: os.Path, contents: String): Unit =
    if !os.exists(path) then
      try os.write(path, contents)
      catch
        case _: java.nio.file.FileAlreadyExistsException =>
          () // lost the create race; content is identical
