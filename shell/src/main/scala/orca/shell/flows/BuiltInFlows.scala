package orca.shell.flows

import orca.shell.OrcaBuild

/** Bundles the built-in flows (ADR 0021 §7) as jar resources under
  * `/orca/shell/flows/` (see build.sbt's resource generator) and extracts them
  * to a real path on disk so scala-cli can run them. A generated `index`
  * resource (newline-separated filenames) drives the listing, since jar
  * resources aren't listable directly.
  */
private[shell] object BuiltInFlows:
  private val resourcePrefix = "/orca/shell/flows/"

  // Per-process memo of [[extracted]]'s result. The snapshot path
  // re-materializes on every call, and callers reach `extracted` on every flow
  // listing (every picker open); once per process is as often as that can
  // matter, since a process's build and flow resources are both fixed at
  // startup. Keyed by the target dir — a total function of the cacheHome/build
  // arguments — so a call with different arguments gets its own extraction
  // rather than the first one's, keeping `extracted` reusable across homes and
  // builds within one process. `computeIfAbsent` keeps the first extraction
  // for a given key atomic under concurrent callers.
  private val extractedCache =
    new java.util.concurrent.ConcurrentHashMap[os.Path, os.Path]()

  private val depPin =
    s"""^//> using dep "${scala.util.matching.Regex.quote(
        OrcaBuild.Module
      )}:[^"]+"$$""".r

  /** The bundled flows' filenames, from the generated index resource. Visible
    * to the package so `BuiltInFlowsCompileTest` can register one test per flow
    * without extracting first.
    */
  private[flows] def names: List[String] =
    resourceText("index").linesIterator.filter(_.nonEmpty).toList

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  /** Extracts the built-in flows to `<cacheHome>/orca/shell/<version>/flows`
    * and returns that directory.
    *
    * A [[OrcaBuild.Release]] extracts once, keyed by the directory being
    * *complete* — present with every indexed flow file — not merely existing; a
    * second call is then a no-op, since a release's flows are immutable for
    * that version. A [[OrcaBuild.Snapshot]] always re-extracts and replaces
    * each flow's orca `//> using dep` line with the build's
    * [[OrcaBuild.usingDirectives]] — the same treatment `_seed_lib.sh --local`
    * applies, so built-ins resolve against the locally published build. It
    * can't reuse the release path's completeness key: a snapshot version
    * doesn't identify its flow content, since a dynver snapshot's dirty
    * timestamp is minute-granular, so republishing within a minute reuses it.
    *
    * Both paths write through [[materialize]], which stages every file in a
    * temp sibling directory and swaps it into place with an atomic move —
    * `ProgressStore.writeLog`'s temp-file-then-`os.move` idiom, applied to a
    * directory. A process killed mid-extraction (OOM, `kill -9`, disk full) can
    * therefore only ever leave the temp directory (never looked at again)
    * half-written, never `dir` itself; combined with the completeness check, a
    * legacy half-populated `dir` left by the old existence-keyed logic is
    * treated as absent and self-heals on the next call.
    *
    * Memoized per process ([[extractedCache]]): repeat calls with the same
    * cacheHome/build — every picker open in one shell process — reuse the first
    * call's extraction.
    */
  def extracted(cacheHome: os.Path, build: OrcaBuild): os.Path =
    val dir = cacheHome / "orca" / "shell" / build.version / "flows"

    extractedCache.computeIfAbsent(
      dir,
      target =>
        val expectedNames = names
        build match
          case OrcaBuild.Release(_) =>
            if !isComplete(target, expectedNames) then
              materialize(target, expectedNames, resourceText)
          case OrcaBuild.Snapshot(_) =>
            materialize(
              target,
              expectedNames,
              name => pinToBuild(resourceText(name), build)
            )
        target
    )

  /** `dir` holds every one of `expectedNames` — the idempotency key for the
    * release path. Anything short of that (absent, or missing a file because a
    * prior extraction died mid-way) is treated the same as "not yet extracted".
    */
  private def isComplete(dir: os.Path, expectedNames: List[String]): Boolean =
    os.isDir(dir) && expectedNames.forall(name => os.isFile(dir / name))

  /** Writes `expectedNames` (via `content`) into a fresh temp directory next to
    * `dir`, then swaps it into place. The swap removes any existing `dir`
    * first: a plain `os.move(replaceExisting = true)` only replaces an *empty*
    * directory, and a half-populated leftover isn't empty. The removal isn't
    * itself atomic with the move, but the window it opens is harmless — at
    * worst a crash there leaves `dir` absent, which is exactly the "not yet
    * extracted" state `extracted` already re-extracts from, so a half-populated
    * `dir` can never result.
    *
    * Always swaps after staging, even into a `dir` that a racing call (this
    * process racing itself, or a concurrent process) completed in the meantime
    * — the outer completeness check in `extracted` already skips this call on
    * the release path once `dir` is complete, and release-path content is a
    * pure function of `version`, so a racing swap is equally valid content.
    * Accepted residual: the remove+move pair here is not atomic, so a reader
    * mid-`os.read` during that window can see `dir` transiently absent;
    * harmless for the release path's single first-ever extraction, and an
    * accepted dev-build-only window otherwise, since the dev path re-extracts
    * and re-swaps on every call.
    */
  private def materialize(
      dir: os.Path,
      expectedNames: List[String],
      content: String => String
  ): Unit =
    val parent = dir / os.up
    os.makeDir.all(parent)
    val tmp =
      os.temp.dir(dir = parent, prefix = s".${dir.last}.", deleteOnExit = false)
    try
      expectedNames.foreach(name => os.write(tmp / name, content(name)))
      if os.exists(dir) then os.remove.all(dir)
      try os.move(tmp, dir, atomicMove = true)
      catch
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          os.move(tmp, dir)
    finally if os.exists(tmp) then os.remove.all(tmp)

  /** Replaces the orca `using dep` pin line with `build`'s
    * [[OrcaBuild.usingDirectives]] — `_seed_lib.sh --local`'s sed treatment,
    * replicated here.
    */
  private def pinToBuild(content: String, build: OrcaBuild): String =
    content
      .split("\n", -1)
      .map(line => if depPin.matches(line) then build.usingDirectives else line)
      .mkString("\n")
