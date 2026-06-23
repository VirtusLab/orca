package orca.plan

import orca.plan.Title
import orca.llm.{JsonData}

/** A single task in a [[Plan]].
  *
  *   - `title` is the one-line human-readable label rendered in the event log
  *     and used as the `## Task: …` markdown section header.
  *   - `description` is the longer instruction handed to the implementing
  *     agent.
  *   - `completed` is a checkbox surfaced in the cosmetic rendered checklist
  *     (ADR 0018 §2.8); resume reads the stage log, not this flag.
  */
case class Task(
    title: Title,
    description: String,
    completed: Boolean = false
) derives JsonData:
  def markComplete: Task = copy(completed = true)
