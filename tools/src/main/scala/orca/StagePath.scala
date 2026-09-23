package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Where something sits in a run's stage tree: at the flow body's root, or
  * inside one stage, identified by that stage's [[StagePath.Id]].
  *
  * [[StagePath.value]] is the persisted spelling — the flow body's is the empty
  * string, and [[StagePath.fromValue]] is the single place that reads it back,
  * so no consumer re-decides what an empty or absent id means.
  */
enum StagePath:
  case FlowBody
  case Stage(id: StagePath.Id)

  /** The persisted spelling of this path; empty at the flow body. */
  def value: String = this match
    case FlowBody  => ""
    case Stage(id) => id.value

  /** The path of a stage named `name` opening directly under this one as the
    * `occurrence`-th stage of that name in this scope.
    */
  def child(name: String, occurrence: Int): StagePath.Stage =
    val segment = s"$name#$occurrence"
    StagePath.Stage(StagePath.Id(this match
      case FlowBody  => segment
      case Stage(id) => s"${id.value}/$segment"
    ))

object StagePath:
  /** Read back a persisted path id. */
  def fromValue(value: String): StagePath =
    if value.isEmpty then FlowBody else Stage(Id(value))

  /** On the wire a path is its [[value]] string, so a persisted document holds
    * the same spelling `SessionRecord.stage` does.
    */
  given codec: JsonValueCodec[StagePath] = new JsonValueCodec[StagePath]:
    def decodeValue(in: JsonReader, default: StagePath): StagePath =
      in.readString(null) match
        case null => in.decodeError("expected a stage path")
        case s    => fromValue(s)
    def encodeValue(x: StagePath, out: JsonWriter): Unit = out.writeVal(x.value)
    def nullValue: StagePath = null

  /** The id of one stage: `name#occurrence` segments, one per enclosing stage,
    * joined by `/` (ADR 0018 §2.1). Compared for exact equality, never parsed
    * back into names or occurrence numbers.
    *
    * Never empty, which is what keeps [[StagePath.Stage]] distinct from
    * [[StagePath.FlowBody]]: the empty spelling is the flow body's, so a stage
    * carrying it would persist as a flow body and read back as one. Nested in
    * [[StagePath]] so that `private[StagePath]` can close the constructor over
    * the two minting doors, which is what holds that invariant.
    */
  opaque type Id = String

  object Id:
    /** The id spelled `id`, refusing the flow body's empty spelling. Reachable
      * only from [[StagePath.child]], which appends a `#occurrence` segment,
      * and [[StagePath.fromValue]], which routes an empty spelling to
      * [[StagePath.FlowBody]].
      */
    private[StagePath] def apply(id: String): Id =
      require(id.nonEmpty, "stage path id must be non-empty")
      id

    extension (id: Id) def value: String = id
