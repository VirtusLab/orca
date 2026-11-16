package orca.util

import orca.effect.FileSystem
import orca.safe.prelude.{*, given}

/** Whole-file replacement that a kill cannot tear: the content goes to a temp
  * file, is flushed to disk, then renamed over the target. A reader sees the
  * old content or the new, never a mix; each write costs an fsync.
  */
private[orca] object AtomicFile:

  /** Replace `path`'s content with `bytes` via a temp file in `stagingDir`,
    * which must be on the same filesystem as `path`. The rename replaces a
    * symlink at `path` rather than writing through it. `path` is untouched and
    * the temp file gone when this throws.
    */
  def replace(
      fs: FileSystem,
      path: os.Path,
      stagingDir: os.Path,
      bytes: IArray[Byte]
  ): Unit =
    val tmp = stagingDir / s".${path.last}.${randomUuid()}.tmp"
    try
      fs.writeNewDurably(tmp, bytes)
      try fs.moveAtomically(tmp, path)
      catch
        // Some filesystems (network mounts, some container overlay/bind mounts)
        // reject ATOMIC_MOVE even for a same-directory rename. Torn writes are
        // impossible there anyway (only the atomicity guarantee against
        // concurrent readers is unavailable), so a plain move is a safe
        // fallback.
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          fs.moveReplacing(tmp, path)
    catch
      case NonFatal(e) =>
        if fs.exists(tmp) then fs.remove(tmp)
        throw e
