package orca.review

import orca.agents.JsonData
import orca.plan.Title

/** A single review finding. `title` is the one-line user-facing label (rendered
  * in the event log under `▶`); `description` is the longer form fed back to
  * the fixing agent and includes whatever context the reviewer gave. The split
  * mirrors `Plan.Task`'s title/description pair so flow scripts that handle
  * issues and tasks can use the same field names.
  */
case class ReviewIssue(
    severity: Severity,
    confidence: Double,
    title: Title,
    description: String,
    file: Option[String],
    line: Option[Int],
    suggestion: Option[String]
) derives JsonData
