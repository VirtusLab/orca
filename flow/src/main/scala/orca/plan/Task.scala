package orca.plan

import orca.plan.Title
import orca.agents.{JsonData}

/** A single task in a [[Plan]].
  *
  *   - `title` is the one-line human-readable label rendered in the event log
  *     and used as the `## Task: …` markdown section header.
  *   - `description` is the longer instruction handed to the implementing
  *     agent.
  */
case class Task(
    title: Title,
    description: String
) derives JsonData
