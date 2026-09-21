package orca

/** Where something sits in a run's stage tree: at the flow body's root, or
  * inside one stage, identified by that stage's [[StagePathId]].
  *
  * [[StagePath.value]] is the persisted spelling — the flow body's is the empty
  * string, and [[StagePath.fromValue]] is the single place that reads it back,
  * so no consumer re-decides what an empty or absent id means.
  */
enum StagePath:
  case FlowBody
  case Stage(id: StagePathId)

  /** The persisted spelling of this path; empty at the flow body. */
  def value: String = this match
    case FlowBody  => ""
    case Stage(id) => id.value

  /** The path of a stage named `name` opening directly under this one as the
    * `occurrence`-th stage of that name in this scope.
    */
  def child(name: String, occurrence: Int): StagePath.Stage =
    val segment = s"$name#$occurrence"
    StagePath.Stage(StagePathId(this match
      case FlowBody  => segment
      case Stage(id) => s"${id.value}/$segment"
    ))

object StagePath:
  /** Read back a persisted path id. */
  def fromValue(value: String): StagePath =
    if value.isEmpty then FlowBody else Stage(StagePathId(value))

  /** Read back a path id a persisted shape may omit: absent reads as the flow
    * body, exactly as an explicitly empty id does.
    */
  def fromValue(value: Option[String]): StagePath =
    value.fold(FlowBody)(fromValue)

/** The id of one stage: `name#occurrence` segments, one per enclosing stage,
  * joined by `/` (ADR 0018 §2.1). Compared for exact equality, never parsed
  * back into names or occurrence numbers.
  *
  * Never empty, which is what keeps [[StagePath.Stage]] distinct from
  * [[StagePath.FlowBody]]: the empty spelling is the flow body's, so a stage
  * carrying it would persist as a flow body and read back as one. Lives beside
  * [[StagePath]] because that invariant is the reason the type exists.
  */
opaque type StagePathId = String

object StagePathId:
  /** The id spelled `id`, refusing the flow body's empty spelling. Minting is
    * internal to orca: [[StagePath.child]], which appends a `#occurrence`
    * segment, and [[StagePath.fromValue]], which routes an empty spelling to
    * [[StagePath.FlowBody]], are its only callers.
    */
  private[orca] def apply(id: String): StagePathId =
    require(id.nonEmpty, "stage path id must be non-empty")
    id

  extension (id: StagePathId) def value: String = id
