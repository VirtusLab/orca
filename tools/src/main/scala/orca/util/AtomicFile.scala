package orca.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

import scala.util.Using
import scala.util.control.NonFatal

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
  def replace(path: os.Path, stagingDir: os.Path, bytes: Array[Byte]): Unit =
    val tmp = stagingDir / s".${path.last}.${UUID.randomUUID()}.tmp"
    try
      writeDurably(tmp, bytes)
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

  /** Create `file` and flush `bytes` to disk before returning. `CREATE_NEW`
    * rather than `os.temp`, so the file gets the umask's permissions, not
    * owner-only ones the rename would carry onto the target.
    */
  private def writeDurably(file: os.Path, bytes: Array[Byte]): Unit =
    Using.resource(
      FileChannel.open(
        file.toNIO,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
    ): channel =>
      val buffer = ByteBuffer.wrap(bytes)
      while buffer.hasRemaining do channel.write(buffer): Unit
      channel.force(true)
