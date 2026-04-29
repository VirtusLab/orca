package orca.plan.simple

import orca.{FlowContext, JsonData, OrcaEvent, given}

/** A single task in the plan. `summary` is a short user-facing label
  * (used for the implement-stage name and the printed plan list);
  * `prompt` is the longer instruction sent verbatim to the LLM.
  *
  * Aim for `summary` around 60 characters — anything longer
  * truncates in the status bar (and crowds the event log).
  */
case class Task(
    branchName: String,
    summary: String,
    prompt: String
) derives JsonData

/** A list of tasks the agent should work through in order. Plans
  * stored on disk use a richer markdown-backed representation; see
  * [[orca.plan.extended]] for that.
  *
  * The "simple" variant fits in one LLM round-trip: the agent
  * produces the JSON; the runtime parses it; the flow iterates.
  */
case class Plan(tasks: List[Task]) derives JsonData:
  /** Surface the plan to the active interaction channel as a single
    * multi-line `Step`. Goes through the event bus rather than
    * `println` so the output works against any channel — terminal,
    * Slack, an HTTP subscriber — without each flow author needing to
    * think about which one is wired.
    *
    * No-op on an empty plan: a `Plan(Nil)` from the planner is a
    * planning failure worth surfacing where it happened, not a thing
    * to render quietly.
    */
  def logTo(using ctx: FlowContext): Unit =
    if tasks.nonEmpty then
      val plural = if tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${tasks.size} task$plural on branch '${tasks.head.branchName}':"
      val body = tasks.map(t => s"  - ${t.summary}").mkString("\n")
      ctx.emit(OrcaEvent.Step(s"$header\n$body"))
