package orca.plan

import orca.plan.Title
import orca.agents.{JsonData}

/** A single task in a [[Plan]] — one decomposed sub-item of a plan, distinct
  * from the CLI's positional `task` argument (`OrcaArgs.userPrompt`), which is
  * the whole run's instruction handed to `Plan.autonomous`/`interactive` in the
  * first place.
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
