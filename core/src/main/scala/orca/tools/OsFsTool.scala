package orca.tools

import orca.FsTool

import java.nio.file.FileSystems

/** Filesystem tool implementation backed by os-lib. Paths are resolved against
  * the working directory when relative. `list` accepts a glob pattern (e.g.
  * `src/**/*.scala`) using the JVM's default glob syntax.
  */
class OsFsTool(base: os.Path = os.pwd) extends FsTool:

  def read(path: String): String =
    os.read(resolve(path))

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
