package orca.plan

import orca.plan.Title
import orca.llm.{JsonData}

/** A single task in a [[Plan]]. The same type covers both the in-memory
  * (`Plan.from`) and markdown-backed (`Plan.loadOrGenerate`) shapes.
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
