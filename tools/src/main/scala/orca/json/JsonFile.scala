package orca.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import orca.OrcaDir
import orca.effect.FileSystem
import orca.safe.prelude.NonFatal

/** Reads whole-file JSON documents that an earlier process wrote, such as the
  * progress log ([[JsonFile.write]]).
  */
final class JsonFile(fs: FileSystem):
  import JsonFile.*

  def read[A](path: os.Path)(using JsonValueCodec[A]): Read[A] =
    readBytes(path) match
      case Left(reason)       => Read.Unreadable(reason)
      case Right(None)        => Read.Absent
      case Right(Some(bytes)) =>
        decode[A](bytes).fold(Read.Corrupt(_), Read.Loaded(_))

  /** The file's raw content, `None` when there is no file, `Left` with the
    * reason when it could not be read.
    */
  private[orca] def readBytes(
      path: os.Path
  ): Either[String, Option[IArray[Byte]]] =
    // Absence is the read's own `NoSuchFileException` rather than an
    // `exists` pre-check, so there is no window between the two for the file
    // to vanish in — the progress log is removed by teardown while the runtime
    // still reads it.
    try Right(Some(fs.readBytes(path)))
    catch
      case _: java.nio.file.NoSuchFileException => Right(None)
      case NonFatal(e)                          => Left(describe(e))

/** Whole-file JSON documents that a later process reads back. Each is rewritten
  * whole on every change, so [[write]] replaces it atomically — a plain
  * overwrite torn by a kill would leave the reader unparseable content where a
  * resume was expected.
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

  /** `bytes` as an `A`, or why they do not parse. */
  private[orca] def decode[A](bytes: IArray[Byte])(using
      JsonValueCodec[A]
  ): Either[String, A] =
    try Right(JsonCodec.readBytes[A](bytes))
    catch case NonFatal(e) => Left(describe(e))

  /** Replace `file`'s contents with `value`'s JSON, atomically (see
    * [[OrcaDir.OrcaFile.replace]]).
    */
  private[orca] def write[A](file: OrcaDir.OrcaFile, value: A)(using
      JsonValueCodec[A]
  ): Unit =
    file.replace(JsonCodec.writeBytes(value))

  /** A one-line account of `e` for a warning: its class and the first line of
    * its message. jsoniter appends a multi-line hex dump of the buffer to its
    * messages, which would otherwise paint over whatever prints the warning.
    */
  private def describe(e: Throwable): String =
    val firstLine =
      Option(e.getMessage).flatMap(_.linesIterator.nextOption()).getOrElse("")
    s"${e.getClass.getSimpleName}: $firstLine"
