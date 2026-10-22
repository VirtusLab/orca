package orca.runlog

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  JsonValueCodec
}
import orca.effect.FileSystem
import orca.json.JsonCodec

import java.io.{IOException, UncheckedIOException}
import java.nio.file.NoSuchFileException

/** Reads a run's event log. Lines that do not decode — a torn last line after a
  * crash, an unknown `type` — are skipped. An absent or unreadable file reads
  * as no events.
  */
private[orca] final class RunEventReader(fs: FileSystem):

  /** Every event in `path`, in file order. */
  def read(path: os.Path): List[RunEvent] = readOrError(path).getOrElse(Nil)

  /** [[read]], but an unreadable file is the error that stopped the read; an
    * absent file still reads as no events.
    */
  def readOrError(
      path: os.Path
  ): Either[IOException | UncheckedIOException, List[RunEvent]] =
    decodedLines(path)(RunEvent.decodeLine)

  /** The events in `path` whose case is one of `types`, in file order. Other
    * lines are not decoded in full, so this is cheaper than [[read]] when few
    * lines match.
    */
  def readOnly(
      path: os.Path,
      types: Set[Class[? <: RunEvent]]
  ): List[RunEvent] =
    val names = types.map(_.getSimpleName)
    decodedLines(path)(line =>
      typeOf(line)
        .filter(names.contains)
        .flatMap(_ => RunEvent.decodeLine(line))
    ).getOrElse(Nil)

  private def decodedLines(path: os.Path)(
      decode: String => Option[RunEvent]
  ): Either[IOException | UncheckedIOException, List[RunEvent]] =
    try Right(fs.collectLines(path)(decode))
    catch
      case _: NoSuchFileException                  => Right(Nil)
      case e: (IOException | UncheckedIOException) => Left(e)

  private def typeOf(line: String): Option[String] =
    try
      Some(
        JsonCodec.readString(line)(using RunEventReader.typeOnlyCodec).`type`
      )
    catch case _: JsonReaderException => None

private object RunEventReader:
  case class TypeOnly(`type`: String)

  val typeOnlyCodec: JsonValueCodec[TypeOnly] = JsonCodec.make
