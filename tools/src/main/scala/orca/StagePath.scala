package orca

/** Where something sits in a run's stage tree: at the flow body's root, or
  * inside one stage, identified by that stage's hierarchical path id
  * (`name#occurrence` segments joined by `/`, ADR 0018 §2.1).
  *
  * The id is opaque: built by [[StagePath.child]], compared for exact equality,
  * never parsed back into names or occurrence numbers. [[StagePath.value]] is
  * its persisted spelling — the flow body's is the empty string, and
  * [[StagePath.fromValue]] is the single place that reads it back, so no
  * consumer re-decides what an empty or absent id means.
  */
enum StagePath:
  case FlowBody
  case Stage(id: String)

  /** The persisted spelling of this path; empty at the flow body. */
  def value: String = this match
    case FlowBody  => ""
    case Stage(id) => id

  /** The path of a stage named `name` opening directly under this one as the
    * `occurrence`-th stage of that name in this scope.
    */
  def child(name: String, occurrence: Int): StagePath.Stage =
    val segment = s"$name#$occurrence"
    StagePath.Stage(this match
      case FlowBody  => segment
      case Stage(id) => s"$id/$segment"
    )

object StagePath:
  /** Read back a persisted path id. */
  def fromValue(value: String): StagePath =
    if value.isEmpty then FlowBody else Stage(value)

  /** Read back a path id a persisted shape may omit: absent reads as the flow
    * body, exactly as an explicitly empty id does.
    */
  def fromValue(value: Option[String]): StagePath =
    value.fold(FlowBody)(fromValue)
