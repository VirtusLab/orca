package orca.effect

import language.experimental.captureChecking

import caps.assumeSafe
import ox.ResourceScope

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import scala.util.Using
import scala.util.control.NonFatal

/** Authority to read and write the local filesystem. */
@assumeSafe
final class FileSystem private () extends caps.SharedCapability:
  def exists(p: os.Path): Boolean = os.exists(p)

  /** [[exists]] without following a final symlink: a dangling link counts. */
  def entryExists(p: os.Path): Boolean = os.exists(p, followLinks = false)
  def isFile(p: os.Path): Boolean = os.isFile(p)
  def isDir(p: os.Path): Boolean = os.isDir(p)
  def isLink(p: os.Path): Boolean = os.isLink(p)
  def read(p: os.Path): String = os.read(p)

  /** Throws `java.nio.file.NoSuchFileException` when `p` is absent. */
  def readBytes(p: os.Path): IArray[Byte] =
    // Nothing else holds the array, so the immutable view captures nothing.
    caps.unsafe.unsafeAssumePure(IArray.unsafeFromArray(os.read.bytes(p)))

  /** At most the first `maxBytes` bytes of `p`. */
  def readPrefix(p: os.Path, maxBytes: Int): IArray[Byte] =
    caps.unsafe.unsafeAssumePure(
      IArray.unsafeFromArray(os.read.bytes(p, offset = 0, count = maxBytes))
    )

  def modifiedAt(p: os.Path): Instant = Instant.ofEpochMilli(os.mtime(p))

  /** Replaces `p`'s content, creating missing parent directories. */
  def writeOver(p: os.Path, content: String): Unit =
    os.write.over(p, content, createFolders = true)

  /** Creates `p` with `content`; throws
    * `java.nio.file.FileAlreadyExistsException` when `p` exists.
    */
  def writeNew(p: os.Path, content: String): Unit = os.write(p, content)

  /** [[writeNew]] for bytes, flushed to disk before returning. The file gets
    * the umask's permissions.
    */
  def writeNewDurably(p: os.Path, bytes: IArray[Byte]): Unit =
    Using.resource(
      FileChannel.open(
        p.toNIO,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
    ): channel =>
      val buffer = ByteBuffer.wrap(IArray.genericWrapArray(bytes).toArray)
      while buffer.hasRemaining do channel.write(buffer): Unit
      channel.force(true)

  /** Renames `from` over `to` atomically, replacing a symlink at `to` rather
    * than writing through it. Throws
    * `java.nio.file.AtomicMoveNotSupportedException` where the filesystem
    * cannot.
    */
  def moveAtomically(from: os.Path, to: os.Path): Unit =
    os.move(from, to, replaceExisting = true, atomicMove = true)

  /** Moves `from` over `to`, replacing it. */
  def moveReplacing(from: os.Path, to: os.Path): Unit =
    os.move(from, to, replaceExisting = true)

  def remove(p: os.Path): Unit = os.remove(p): Unit
  def append(p: os.Path, text: String): Unit = os.write.append(p, text)

  /** At most the last `maxBytes` bytes of `p`. */
  def readSuffix(p: os.Path, maxBytes: Int): IArray[Byte] =
    val size = os.size(p)
    val count = math.min(size, maxBytes.toLong).toInt
    caps.unsafe.unsafeAssumePure(
      IArray.unsafeFromArray(os.read.bytes(p, offset = size - count, count))
    )

  /** `f`'s defined results over the lines of `p`, read without holding the
    * whole file.
    */
  def collectLines[A](p: os.Path)(f: String => Option[A]): List[A] =
    os.read.lines.stream(p).flatMap(f(_)).toList

  /** Removes `p` with everything under it; a symlink is removed, not followed.
    */
  def removeAll(p: os.Path): Unit = os.remove.all(p)

  /** Creates `p` and its missing parents; a no-op for an existing directory.
    */
  def makeDirs(p: os.Path): Unit = os.makeDir.all(p)

  /** Every file under `root`, recursively, that `keep` accepts. */
  def files(root: os.Path, keep: os.Path => Boolean): List[os.Path] =
    os.walk.stream(root).filter(p => keep(p) && os.isFile(p)).toList

  /** Some file under `root`, recursively, that `keep` accepts; stops at the
    * first.
    */
  def findFile(root: os.Path, keep: os.Path => Boolean): Option[os.Path] =
    os.walk.stream(root).find(p => keep(p) && os.isFile(p))

  /** The entries directly inside `dir`. */
  def list(dir: os.Path): IndexedSeq[os.Path] = os.list(dir)

  /** `p` with symlinks resolved; `p` must exist. */
  def realPath(p: os.Path): os.Path = os.Path(p.toNIO.toRealPath())

  /** A temp file holding `contents`, in `dir` or else the system temp
    * directory, removed when the enclosing scope ends.
    */
  def tempFile(
      contents: String,
      prefix: String,
      suffix: String,
      dir: Option[os.Path] = None
  )(using ResourceScope): os.Path =
    scoped(
      os.temp(
        contents,
        dir = dir.orNull,
        prefix = prefix,
        suffix = suffix,
        deleteOnExit = false
      )
    )(os.remove(_): Unit)

  /** A temp directory, removed with its contents when the enclosing scope ends.
    */
  def tempDir(prefix: String)(using ResourceScope): os.Path =
    scoped(os.temp.dir(prefix = prefix, deleteOnExit = false))(os.remove.all(_))

  // Temp paths skip `deleteOnExit`: each registration would stay in the JVM's
  // exit-hook list for the rest of a flow that runs hundreds of turns. A
  // failing removal is logged: by then the scope's work has its outcome.
  private def scoped(path: os.Path)(remove: os.Path => Unit)(using
      ResourceScope
  ): os.Path =
    ox.useInScope(path): p =>
      try remove(p)
      catch case NonFatal(e) => FileSystem.log.warn(s"removing $p failed", e)

object FileSystem:
  private val log = org.slf4j.LoggerFactory.getLogger("orca.effect")

  /** For wiring and tests only: safe code cannot call this. */
  private[orca] def ambient: FileSystem = new FileSystem()
