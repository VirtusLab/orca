package orca.review

import orca.agents.{JsonData, given}
import orca.plan.Title

import sttp.tapir.Schema.annotations.description

/** Where a [[ReviewIssue]] points in the diff. `line` narrows further within
  * `file` when the reviewer names one — a line without a file isn't a
  * representable location, so the two can no longer appear independently.
  */
case class Location(file: String, line: Option[Int]) derives JsonData

/** A single review finding. `title` is the one-line user-facing label (rendered
  * in the event log under `▶`); `description` is the longer form fed back to
  * the fixing agent. The split mirrors `Plan.Task`'s title/description pair so
  * flow scripts handling issues and tasks share field names.
  *
  * `severity` and `confidence` are orthogonal: impact if the finding is real,
  * versus how sure the reviewer is that it is. [[ConfidenceGate]] reads both.
  *
  * @param confidence
  *   a probability in `[0, 1]`; the contract reviewers are held to is stated in
  *   full in `resources/orca/review/prompts/initial-review.md`, and the
  *   `@description` below is its schema-side copy (`ReviewLoopPromptsTest` pins
  *   the two together).
  */
case class ReviewIssue(
    severity: Severity,
    @description(
      "Probability in [0,1] that this finding is real and worth fixing, " +
        "based only on the evidence you gathered — not on deference to the " +
        "task's plan, and not a prediction of whether it will be accepted."
    )
    confidence: Double,
    title: Title,
    description: String,
    location: Option[Location],
    suggestion: Option[String]
) derives JsonData
