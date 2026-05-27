package orca.tools

import java.nio.file.FileSystems

/** Filesystem adapter usable from flow scripts — the handle behind the `fs`
  * accessor. Reads, writes, and globs files against the flow's working
  * directory.
  *
  * All paths passed to `read`, `write`, and `list` are resolved relative to the
  * flow's working directory unless they are absolute. `list` accepts a glob
  * pattern (e.g. `src/**/*.scala`) following the JVM's default glob syntax and
  * returns matching file paths as strings relative to the same working
  * directory.
  */
trait FsTool:

  /** Read the file at `path`. Returns `None` when no file exists at that
    * location — a recoverable miss the caller can branch on. Throws for
    * system-level failures (permission, IO).
    */
  def read(path: String): Option[String]

  def write(path: String, content: String): Unit
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

  def write(path: String, content: String): Unit =
    os.write.over(resolve(path), content, createFolders = true)

  def list(glob: String): List[String] =
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
