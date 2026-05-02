package orca.plan

import orca.{JsonData, Title}

/** A single task in a development plan, shared by [[SimplePlan]] (in- memory)
  * and [[ExtendedPlan]] (markdown-backed).
  *
  *   - `title` is the one-line human-readable label rendered in the event log
  *     and used as the `## Task: …` markdown section header.
  *   - `description` is the longer instruction handed to the implementing
  *     agent.
  *   - `completed` is the resume-state checkbox used by extended plans. Simple
  *     plans run end-to-end and leave it false.
  */
case class Task(
    title: Title,
    description: String,
    completed: Boolean = false
) derives JsonData:
  def markComplete: Task = copy(completed = true)
