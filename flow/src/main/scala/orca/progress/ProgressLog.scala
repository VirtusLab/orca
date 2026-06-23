package orca.progress

import orca.llm.{JsonData, given}

/** Header capturing the git context in which the progress log was started. */
case class ProgressHeader(
    startingBranch: String,
    branch: String,
    promptHash: String
) derives JsonData

/** A single stage's outcome, stored as an already-serialised JSON string.
  *
  * `resultJson` is type-erased at rest — the log is heterogeneous across stage
  * types. Deserialisation back to a typed value happens at the stage call site
  * (C3), not here.
  */
case class StageEntry(id: String, name: String, resultJson: String)
    derives JsonData

/** An append-only log of stage outcomes for one flow run, keyed by its header.
  */
case class ProgressLog(header: ProgressHeader, entries: List[StageEntry])
    derives JsonData
