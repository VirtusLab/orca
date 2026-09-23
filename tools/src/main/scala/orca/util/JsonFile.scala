package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromArray,
  writeToString
}

import scala.util.control.NonFatal

/** Whole-file JSON documents that a later process reads back: the progress log,
  * the durable session records, the attempt manifest. Each is rewritten whole
  * on every change, so [[write]] goes through a temp file and a rename — a
  * plain `os.write.over` torn by a kill would leave the reader unparseable
  * content where a resume was expected.
  */
object JsonFile:

  /** Outcome of [[read]]. The two failure arms call for opposite actions:
    * content orca wrote and cannot parse is safe to replace, while a file it
    * could not read at all may still hold data, and overwriting it would
    * destroy that.
    */
  enum Read[+A]:
    /** No file at the path. */
    case Absent

    /** The read itself failed — permissions, or a directory at the path. */
    case Unreadable(reason: String)

    /** The file was read but does not parse: truncated, or externally edited.
      */
    case Corrupt(reason: String)

    case Loaded(value: A)

  def read[A](path: os.Path)(using JsonValueCodec[A]): Read[A] =
    readBytes(path) match
      case Read.Loaded(bytes)      => decode(bytes)
      case Read.Absent             => Read.Absent
      case Read.Unreadable(reason) => Read.Unreadable(reason)
      case Read.Corrupt(reason)    => Read.Corrupt(reason)

  /** The file's raw content, for a caller that must keep the exact bytes it
    * decodes. Never `Corrupt`.
    */
  // Absence is the read's own `NoSuchFileException` rather than an `os.exists`
  // pre-check, so there is no window between the two for the file to vanish
  // in — the progress log is removed by teardown while the runtime still
  // reads it.
  def readBytes(path: os.Path): Read[IArray[Byte]] =
    try Read.Loaded(IArray.unsafeFromArray(os.read.bytes(path)))
    catch
      case _: java.nio.file.NoSuchFileException => Read.Absent
      case NonFatal(e)                          => Read.Unreadable(describe(e))

  /** `bytes` as an `A`, or `Corrupt` when they do not parse. */
  def decode[A](bytes: IArray[Byte])(using JsonValueCodec[A]): Read[A] =
    try Read.Loaded(readFromArray[A](IArray.genericWrapArray(bytes).toArray))
    catch case NonFatal(e) => Read.Corrupt(describe(e))

  /** Replace `path`'s contents with `value`'s JSON via a temp file in `tempDir`
    * (which must be on the same filesystem as `path`) renamed over it.
    *
    * The temp file never outlives a failure, and `path` is untouched when one
    * happens.
    */
  def write[A](path: os.Path, tempDir: os.Path, value: A)(using
      JsonValueCodec[A]
  ): Unit =
    val tmp = os.temp(
      contents = writeToString(value),
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

  /** A one-line account of `e` for a warning: its class and the first line of
    * its message. jsoniter appends a multi-line hex dump of the buffer to its
    * messages, which would otherwise paint over whatever prints the warning.
    */
  private def describe(e: Throwable): String =
    val firstLine =
      Option(e.getMessage).flatMap(_.linesIterator.nextOption()).getOrElse("")
    s"${e.getClass.getSimpleName}: $firstLine"
