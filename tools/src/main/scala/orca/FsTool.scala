package orca

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
