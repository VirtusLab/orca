package orca.review

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import orca.plan.Title
import sttp.tapir.Schema

/** A review loop's name for one finding, stable across its rounds: a reviewer
  * re-reporting an open finding names its id in [[ReviewFinding.reopens]], so a
  * reworded title is still the same finding, and two findings sharing a title
  * stay two. A flow's own open findings ([[OpenFinding.custom]]) carry ids of
  * their own, which never equal a loop's.
  */
opaque type FindingId = String

object FindingId:
  private[orca] def apply(s: String): FindingId = s
  extension (id: FindingId) def value: String = id

  /** The id of a finding first reported in `round` under the fix-turn `key`
    * ([[KeyedFinding]]) — unique within a loop, as keys are within a round.
    */
  private[review] def reported(round: Int, key: String): FindingId =
    s"R$round.$key"

  /** The id of the `n`-th (1-based) finding a loop was seeded with. */
  private[review] def seed(n: Int): FindingId = s"S$n"

  /** The id of a finding a flow records itself — the same for two findings
    * exactly when they are one defect: same title and location.
    */
  private[review] def custom(
      title: Title,
      location: Option[Location]
  ): FindingId =
    s"C:${normalisedTitle(title.value)}@${location.fold("")(_.text)}"

  given JsonValueCodec[FindingId] with
    def decodeValue(in: JsonReader, default: FindingId): FindingId =
      in.readString(default)
    def encodeValue(value: FindingId, out: JsonWriter): Unit =
      out.writeVal(value)
    def nullValue: FindingId = null.asInstanceOf[FindingId]

  given Schema[FindingId] = Schema.string[FindingId]
