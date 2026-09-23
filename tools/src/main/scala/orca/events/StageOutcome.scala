package orca.events

/** How a stage announced by [[OrcaEvent.StageStarted]] ended. */
enum StageOutcome:
  /** The body ran and its result was recorded. */
  case Completed

  /** The body or its recording threw; the [[OrcaEvent.Error]] reporting it
    * precedes the end.
    */
  case Failed

  /** The result came from the progress log without running the body. */
  case Replayed
