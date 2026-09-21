package orca.util

import scala.util.control.NonFatal

/** Whole-file writes that a concurrent reader can never catch half-done.
  *
  * Used for orca's small bookkeeping documents (the progress log, the durable
  * session records), each of which is rewritten whole on every change and read
  * back by a resumed run: a plain `os.write.over` torn by a kill leaves the
  * reader with unparseable content where a resume was expected.
  */
private[orca] object AtomicFile:

  /** Replace `path`'s contents with `contents` via a temp file in `tempDir`
    * (which must be on the same filesystem as `path`) renamed over it.
    *
    * The temp file never outlives a failure, and `path` is untouched when one
    * happens.
    */
  def write(path: os.Path, tempDir: os.Path, contents: String): Unit =
    val tmp = os.temp(
      contents = contents,
      dir = tempDir,
      prefix = s".${path.last}.",
      suffix = ".tmp",
      deleteOnExit = false
    )
    try
      try os.move(tmp, path, replaceExisting = true, atomicMove = true)
      catch
        // Some filesystems (network mounts, some container overlay/bind mounts)
        // reject ATOMIC_MOVE even for a same-directory rename. Torn writes are
        // impossible there anyway (only the atomicity guarantee against
        // concurrent readers is unavailable), so a plain move is a safe
        // fallback.
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          os.move(tmp, path, replaceExisting = true)
    catch
      case NonFatal(e) =>
        if os.exists(tmp) then os.remove(tmp): Unit
        throw e
