package orca.tools

import orca.{OrcaFlowException, WorkspaceWrite}

import java.nio.file.FileSystems

/** Filesystem adapter usable from flow scripts — the handle behind the `fs`
  * accessor. Reads, writes, and globs files against the flow's working
  * directory.
  *
  * Paths passed to `read` and `write` are resolved relative to the flow's
  * working directory unless they are absolute (an absolute path is used as-is).
  * `list` does not support this: it only accepts a glob *relative* to the
  * working directory. A leading `/`, or a `.`/`..` path segment, is rejected
  * with [[orca.OrcaFlowException]] at call time rather than resolved — an
  * absolute glob could otherwise only ever silently match nothing (see
  * [[OsFsTool.list]]). `list` accepts a glob pattern (e.g. `src/**/*.scala`)
  * following the JVM's default glob syntax and returns matching file paths as
  * strings relative to the same working directory.
  */
trait FsTool:

  /** Read the file at `path`. Returns `None` when no file exists at that
    * location — a recoverable miss the caller can branch on. Throws for
    * system-level failures (permission, IO).
    */
  def read(path: String): Option[String]

  def write(path: String, content: String)(using WorkspaceWrite): Unit
  def list(glob: String): List[String]

/** `FsTool` implementation backed by os-lib. Path resolution and glob semantics
  * are specified on the trait; this class wires them to `os.read` /
  * `os.write.over` / `os.walk.stream` and narrows the `list` traversal to the
  * deepest wildcard-free prefix of the glob.
  */
private[orca] class OsFsTool(base: os.Path = os.pwd) extends FsTool:

  def read(path: String): Option[String] =
    val p = resolve(path)
    if os.isFile(p) then Some(os.read(p)) else None

  def write(path: String, content: String)(using WorkspaceWrite): Unit =
    os.write.over(resolve(path), content, createFolders = true)

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

  /** Reject glob shapes `globRoot`'s segment fold can't handle cleanly: a
    * leading `/` (os-lib throws `InvalidSegment` walking an empty first
    * segment, or — if that were papered over — the glob would only ever match
    * nothing, since found paths are always relative to `base`) and any `.`/`..`
    * segment (os-lib rejects both outright; `..` in particular has no defined
    * meaning for a glob rooted at `base`). Fails fast with a message naming
    * `list` and the offending glob, rather than letting os-lib's generic
    * `IllegalArgumentException` (no `list`-level context) surface instead.
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

  /** Walk only the deepest directory that contains no wildcards — e.g. for
    * `src/main/**/*.scala` start at `src/main`. Cuts traversal cost for
    * patterns rooted in a subtree.
    */
  private def globRoot(glob: String): os.Path =
    glob
      .split('/')
      .takeWhile(s => !hasGlobMeta(s))
      .foldLeft(base)(_ / _)

  private def hasGlobMeta(segment: String): Boolean =
    segment.contains('*') || segment.contains('?') || segment.contains('[')
