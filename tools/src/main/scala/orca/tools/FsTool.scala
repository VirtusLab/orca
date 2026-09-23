package orca.tools

import orca.{OrcaDir, OrcaFlowException, WorkspaceWrite}

import java.nio.file.FileSystems

/** Filesystem adapter usable from flow scripts — the handle behind the `fs`
  * accessor. Reads, writes, and globs files against the flow's working
  * directory.
  *
  * `read`/`write` paths are resolved relative to the working directory unless
  * absolute. `list` instead takes a glob (JVM default syntax, e.g.
  * `src/**/*.scala`) that must be *relative* to the working directory and
  * returns matching paths relative to it; a leading `/` or a `.`/`..` segment
  * is rejected with [[orca.OrcaFlowException]] rather than resolved (see
  * [[OsFsTool.list]]).
  */
trait FsTool:

  /** Read the file at `path`. `None` when no file exists there — a recoverable
    * miss the caller can branch on. Throws for system-level failures
    * (permission, IO).
    */
  def read(path: String): Option[String]

  /** Write `content` to the file at `path`, creating parent directories.
    * Refuses, with [[orca.OrcaFlowException]], a path that — symlinks resolved
    * — lies outside the working directory or inside a directory orca owns
    * (`.orca/runs`, `.orca/cache`, `.orca/worktrees`).
    */
  def write(path: String, content: String)(using WorkspaceWrite): Unit
  def list(glob: String): List[String]

/** `FsTool` implementation backed by os-lib. Path resolution and glob semantics
  * are specified on the trait; the `list` traversal is narrowed to the deepest
  * wildcard-free prefix of the glob.
  */
private[orca] class OsFsTool(base: os.Path = os.pwd) extends FsTool:

  def read(path: String): Option[String] =
    val p = resolve(path)
    if os.isFile(p) then Some(os.read(p)) else None

  def write(path: String, content: String)(using ws: WorkspaceWrite): Unit =
    ws.check("fs.write")
    val target = resolve(path)
    validateWriteTarget(path, target)
    os.write.over(target, content, createFolders = true)

  def list(glob: String): List[String] =
    validateGlob(glob)
    val matcher =
      FileSystems.getDefault.getPathMatcher(s"glob:$glob")
    val root = globRoot(glob)
    if !os.exists(root) then Nil
    else
      os.walk
        .stream(root)
        .filter(os.isFile)
        .filter(p => matcher.matches(p.relativeTo(base).toNIO))
        .map(p => p.relativeTo(base).toString)
        .toList

  private def resolve(path: String): os.Path =
    os.Path(path, base)

  /** Refuses `target` unless, symlinks resolved, it lies in the working
    * directory and outside the directories orca manages.
    */
  private def validateWriteTarget(path: String, target: os.Path): Unit =
    val realBase = os.Path(base.toNIO.toRealPath())
    val real = realLocation(path, target)
    if !real.startsWith(realBase) then
      throw OrcaFlowException(
        s"fs.write: '$path' resolves outside the flow's working directory " +
          s"$base — write inside it"
      )
    List(
      OrcaDir.runsPath(realBase),
      OrcaDir.cachePath(realBase),
      OrcaDir.worktreesPath(realBase)
    ).find(real.startsWith)
      .foreach: owned =>
        throw OrcaFlowException(
          s"fs.write: '$path' is inside $owned, which orca manages — " +
            "write elsewhere in the working directory"
        )

  /** Where writing `target` lands: the real path of its deepest existing
    * ancestor (itself included), plus the segments still to be created.
    */
  private def realLocation(path: String, target: os.Path): os.Path =
    if os.exists(target) then os.Path(target.toNIO.toRealPath())
    else if os.isLink(target) then
      throw OrcaFlowException(
        s"fs.write: '$path' goes through the dangling symlink $target — " +
          "remove the link or write elsewhere"
      )
    else realLocation(path, target / os.up) / target.last

  /** Reject glob shapes `globRoot`'s segment fold can't handle cleanly: a
    * leading `/` (would only ever match nothing, since found paths are relative
    * to `base`) and any `.`/`..` segment (no defined meaning for a glob rooted
    * at `base`). Fails fast with a message naming `list`, rather than letting
    * os-lib's generic `IllegalArgumentException` surface.
    */
  private def validateGlob(glob: String): Unit =
    if glob.startsWith("/") then
      throw OrcaFlowException(
        s"fs.list: glob must be relative to the flow's working directory, " +
          s"not absolute: '$glob'"
      )
    if glob.split('/').exists(s => s == "." || s == "..") then
      throw OrcaFlowException(
        s"fs.list: glob must not contain '.' or '..' segments: '$glob'"
      )

  /** Walk only the deepest wildcard-free directory — e.g. for
    * `src/main/**/*.scala` start at `src/main` — to cut traversal cost.
    */
  private def globRoot(glob: String): os.Path =
    glob
      .split('/')
      .takeWhile(s => !hasGlobMeta(s))
      .foldLeft(base)(_ / _)

  private def hasGlobMeta(segment: String): Boolean =
    segment.contains('*') || segment.contains('?') || segment.contains('[')
