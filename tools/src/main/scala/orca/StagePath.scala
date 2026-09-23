package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}
import orca.agents.JsonData
import sttp.tapir.Schema

import scala.annotation.tailrec

/** Where something sits in a run's stage tree: at the flow body's root, or
  * inside one stage (ADR 0018 §2.1).
  *
  * Persisted as a JSON array of `{"name", "occurrence"}` segments, outermost
  * first, `[]` being the flow body. A stage name is stored as given, so no name
  * can make two paths equal.
  */
enum StagePath:
  case FlowBody

  /** The `occurrence`-th stage named `name` opened directly under `parent`. */
  case Stage(parent: StagePath, name: String, occurrence: Int)

  /** The path of a stage named `name` opening directly under this one as the
    * `occurrence`-th stage of that name in this scope.
    */
  def child(name: String, occurrence: Int): StagePath.Stage =
    StagePath.Stage(this, name, occurrence)

  /** `name#occurrence` segments joined by `/` (e.g. `outer#0/inner#0`), empty
    * at the flow body. For people only: two paths can display alike.
    */
  def display: String =
    segments.map(s => s"${s.name}#${s.occurrence}").mkString("/")

  private def segments: List[StagePath.Segment] =
    @tailrec
    def loop(
        path: StagePath,
        acc: List[StagePath.Segment]
    ): List[StagePath.Segment] =
      path match
        case FlowBody => acc
        case Stage(parent, name, occurrence) =>
          loop(parent, StagePath.Segment(name, occurrence) :: acc)
    loop(this, Nil)

object StagePath:
  private case class Segment(name: String, occurrence: Int)

  private val segmentsCodec: JsonValueCodec[List[Segment]] =
    JsonCodecMaker.make

  // The list codec reads `null` as its default, which would be the flow body.
  private def decode(in: JsonReader): StagePath =
    if in.isNextToken('n') then in.decodeError("expected a stage path")
    in.rollbackToken()
    segmentsCodec
      .decodeValue(in, Nil)
      .foldLeft(FlowBody: StagePath)((path, s) =>
        path.child(s.name, s.occurrence)
      )

  given codec: JsonValueCodec[StagePath] = new JsonValueCodec[StagePath]:
    def decodeValue(in: JsonReader, default: StagePath): StagePath = decode(in)
    def encodeValue(x: StagePath, out: JsonWriter): Unit =
      segmentsCodec.encodeValue(x.segments, out)
    def nullValue: StagePath = null

  /** A stage's path, refusing the flow body's `[]`. */
  given stageJsonData: JsonData[Stage] = JsonData(
    Schema.schemaForIterable[Segment, List](using Schema.derived).as[Stage],
    new ConfiguredJsonValueCodec[Stage]:
      def decodeValue(in: JsonReader, default: Stage): Stage =
        decode(in) match
          case s: Stage => s
          case FlowBody => in.decodeError("expected a stage path, got []")
      def encodeValue(x: Stage, out: JsonWriter): Unit =
        codec.encodeValue(x, out)
      def nullValue: Stage = null
  )
