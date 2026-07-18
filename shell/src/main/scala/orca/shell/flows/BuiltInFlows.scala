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

  private val depPin = """^//> using dep "org\.virtuslab::orca:[^"]+"$""".r

  /** The bundled flows' filenames, from the generated index resource. */
  private def names: List[String] =
    resourceText("index").linesIterator.filter(_.nonEmpty).toList

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  /** Extracts the built-in flows to
    * `$XDG_CACHE_HOME/orca/shell/<version>/flows` (default `~/.cache/...`,
    * mirroring `GlobalSettings.path`'s env handling: a relative, empty, or
    * root-climbing `XDG_CACHE_HOME` falls back like an unset one). Returns
    * that directory.
    *
    * A release-looking `version` (`ShellVersion.isRelease`) extracts once,
    * keyed by the directory's existence — a second call is a no-op, since a
    * release's flows are immutable for that version. Any other version (a
    * dev snapshot or `"dev"` itself) always re-extracts and rewrites each
    * flow's `//> using dep "org.virtuslab::orca:X"` line to the running
    * `version`, inserting `//> using repository ivy2Local` right after it —
    * the same treatment `_seed_lib.sh --local` applies, so built-ins resolve
    * against a locally published build instead of a not-yet-released Maven
    * Central artifact.
    */
  def extracted(env: String => Option[String], home: os.Path, version: String): os.Path =
    val cacheHome = env("XDG_CACHE_HOME")
      // `os.Path` accepts only absolute paths, so a relative, empty, or
      // root-climbing value throws and falls back — no separate pre-filter.
      .flatMap(v => scala.util.Try(os.Path(v)).toOption)
      .getOrElse(home / ".cache")
    val dir = cacheHome / "orca" / "shell" / version / "flows"

    if ShellVersion.isRelease(version) then
      if !os.isDir(dir) then
        os.makeDir.all(dir)
        names.foreach(name => os.write(dir / name, resourceText(name)))
    else
      os.makeDir.all(dir)
      names.foreach(name => os.write.over(dir / name, pinToRunningVersion(resourceText(name), version)))

    dir

  /** Rewrites the `using dep` pin line to `version` and inserts the
    * ivy2Local repository line right after it — `_seed_lib.sh --local`'s sed
    * treatment, replicated here.
    */
  private def pinToRunningVersion(content: String, version: String): String =
    content
      .split("\n", -1)
      .toList
      .flatMap { line =>
        if depPin.matches(line) then
          List(s"""//> using dep "org.virtuslab::orca:$version"""", "//> using repository ivy2Local")
        else List(line)
      }
      .mkString("\n")
