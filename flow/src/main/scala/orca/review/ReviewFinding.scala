package orca.review

import orca.agents.{JsonData, given}
import orca.plan.Title

/** Where a [[ReviewFinding]] points in the diff. `line` narrows further within
  * `file` when the reviewer names one — a line without a file isn't a
  * representable location, so the two cannot appear independently.
  */
case class Location(file: String, line: Option[Int]) derives JsonData:
  /** `file:line`, or the file alone when no line was named — the form every
    * renderer here points a reader at the code with.
    */
  def text: String = line.fold(file)(l => s"$file:$l")

/** A single review finding. `title` is the one-line user-facing label (rendered
  * in the event log under `▶`); `description` is the longer form fed back to
  * the fixing agent. The split mirrors `Plan.Task`'s title/description pair so
  * flow scripts handling findings and tasks share field names.
  */
case class ReviewFinding(
    title: Title,
    description: String,
    location: Option[Location],
    suggestion: Option[String]
) derives JsonData
