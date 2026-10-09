package orca.plan

import orca.{FlowContext, InStage}
import orca.agents.{Agent, Announce}
import orca.json.{JsonData, given}

/** One part of a [[Roadmap]]: planned into tasks only when its turn comes. */
case class Epic(title: Title, goal: String) derives JsonData

/** A change too large for one [[Plan]], split into ordered epics. `brief` is a
  * codebase briefing shared by every epic's planner and implementers.
  */
case class Roadmap(description: String, epics: List[Epic], brief: String)
    derives JsonData:

  /** The planning input for `epic`: the whole roadmap's goal, the epics already
    * implemented, and this epic's goal. Earlier epics' code is in the
    * repository by then, so the planner is told to read it rather than assume.
    */
  def epicPrompt(epic: Epic): String =
    val done = epics.takeWhile(_ != epic)
    val doneBlock =
      if done.isEmpty then "No epics are implemented yet."
      else
        "Already implemented, in this order:\n" +
          done.map(e => s"- ${e.title}").mkString("\n")
    s"""Overall change: $description
       |
       |$doneBlock
       |
       |Plan only this epic: ${epic.title}
       |${epic.goal}
       |
       |Read the current code first: earlier epics changed it.""".stripMargin

object Roadmap:
  given Announce[Roadmap] = Announce.from: roadmap =>
    if roadmap.epics.isEmpty then ""
    else
      val plural = if roadmap.epics.size == 1 then "" else "s"
      s"Planned ${roadmap.epics.size} epic$plural:\n" +
        roadmap.epics.map(e => s"  - ${e.title}").mkString("\n")

  extension (planned: WithChat[Roadmap])
    /** Have the roadmap critiqued in a fresh conversation, then revised by its
      * planner, as `WithChat[Plan].reviewed` does for a plan.
      */
    def reviewed(
        critiqueInstructions: String = PlanPrompts.RoadmapCritique,
        reviseInstructions: String = PlanPrompts.RoadmapRevise,
        variant: Agent[?] => Agent[?] = identity
    )(using FlowContext, InStage): WithChat[Roadmap] =
      Plan.critiqueThenRevise(
        planned,
        critiqueInstructions,
        reviseInstructions,
        variant,
        render(planned.value)
      )

  /** Markdown for the critique and revise prompts; never parsed back. */
  private def render(roadmap: Roadmap): String =
    val epics = roadmap.epics
      .map(e => s"\n## Epic: ${e.title}\n\n${e.goal.stripLineEnd}\n")
      .mkString
    s"# Roadmap\n\n${roadmap.description.stripLineEnd}\n$epics" +
      s"\n## Brief\n\n${roadmap.brief.stripLineEnd}\n"
