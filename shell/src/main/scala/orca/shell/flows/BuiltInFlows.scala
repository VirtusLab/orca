package orca.shell.flows

import orca.shell.ShellVersion

/** Bundles the six built-in flows (ADR 0021 §7) as jar resources under
  * `/orca/shell/flows/` (see build.sbt's resource generator) and extracts them
  * to a real path on disk so scala-cli can run them. A generated `index`
  * resource (newline-separated filenames) drives the listing, since jar
  * resources aren't listable directly.
  */
object BuiltInFlows:
  private val resourcePrefix = "/orca/shell/flows/"

  private val orcaDepModule = "org.virtuslab::orca"

  private val depPin =
    s"""^//> using dep "${scala.util.matching.Regex.quote(
        orcaDepModule
      )}:[^"]+"$$""".r

  /** The bundled flows' filenames, from the generated index resource. */
  private def names: List[String] =
    resourceText("index").linesIterator.filter(_.nonEmpty).toList

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  /** Extracts the built-in flows to
    * `$XDG_CACHE_HOME/orca/shell/<version>/flows` (default `~/.cache/...`,
    * mirroring `GlobalSettings.path`'s env handling: a relative, empty, or
    * root-climbing `XDG_CACHE_HOME` falls back like an unset one). Returns that
    * directory.
    *
    * A release-looking `version` (`ShellVersion.isRelease`) extracts once,
    * keyed by the directory being *complete* — present with every indexed flow
    * file — not merely existing; a second call is then a no-op, since a
    * release's flows are immutable for that version. Any other version (a dev
    * snapshot or `"dev"` itself) always re-extracts and rewrites each flow's
    * `//> using dep "org.virtuslab::orca:X"` line to the running `version`,
    * inserting `//> using repository ivy2Local` right after it — the same
    * treatment `_seed_lib.sh --local` applies, so built-ins resolve against a
    * locally published build instead of a not-yet-released Maven Central
    * artifact.
    *
    * Both paths write through [[materialize]], which stages every file in a
    * temp sibling directory and swaps it into place with an atomic move —
    * `ProgressStore.writeLog`'s temp-file-then-`os.move` idiom, applied to a
    * directory. A process killed mid-extraction (OOM, `kill -9`, disk full) can
    * therefore only ever leave the temp directory (never looked at again)
    * half-written, never `dir` itself; combined with the completeness check, a
    * legacy half-populated `dir` left by the old existence-keyed logic is
    * treated as absent and self-heals on the next call.
    */
  def extracted(
      env: String => Option[String],
      home: os.Path,
      version: String
  ): os.Path =
    val cacheHome = env("XDG_CACHE_HOME")
      // `os.Path` accepts only absolute paths, so a relative, empty, or
      // root-climbing value throws and falls back — no separate pre-filter.
      .flatMap(v => scala.util.Try(os.Path(v)).toOption)
      .getOrElse(home / ".cache")
    val dir = cacheHome / "orca" / "shell" / version / "flows"
    val expectedNames = names

    if ShellVersion.isRelease(version) then
      if !isComplete(dir, expectedNames) then
        materialize(dir, expectedNames, resourceText)
    else
      materialize(
        dir,
        expectedNames,
        name => pinToRunningVersion(resourceText(name), version)
      )

    dir

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

  /** Rewrites the `using dep` pin line to `version` and inserts the ivy2Local
    * repository line right after it — `_seed_lib.sh --local`'s sed treatment,
    * replicated here.
    */
  private def pinToRunningVersion(content: String, version: String): String =
    content
      .split("\n", -1)
      .toList
      .flatMap { line =>
        if depPin.matches(line) then
          List(
            s"""//> using dep "$orcaDepModule:$version"""",
            "//> using repository ivy2Local"
          )
        else List(line)
      }
      .mkString("\n")
