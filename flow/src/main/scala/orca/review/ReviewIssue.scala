package orca.review

import orca.agents.{JsonData, given}
import orca.plan.Title

/** Where a [[ReviewIssue]] points in the diff. `line` narrows further within
  * `file` when the reviewer names one — a line without a file isn't a
  * representable location, so the two cannot appear independently.
  */
case class Location(file: String, line: Option[Int]) derives JsonData

/** A single review finding. `title` is the one-line user-facing label (rendered
  * in the event log under `▶`); `description` is the longer form fed back to
  * the fixing agent. The split mirrors `Plan.Task`'s title/description pair so
  * flow scripts handling issues and tasks share field names.
  */
case class ReviewIssue(
    title: Title,
    description: String,
    location: Option[Location],
    suggestion: Option[String]
) derives JsonData
