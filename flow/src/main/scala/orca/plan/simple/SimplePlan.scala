package orca.plan.simple

import orca.{Announce, JsonData, given}

/** A single task in the plan. `shortSummary` is the one-line user-facing label
  * (used for the implement-stage name and the printed plan list); `description`
  * is the longer instruction sent verbatim to the LLM. The split mirrors
  * `ReviewIssue`'s shortSummary/description pair so the same naming covers
  * tasks and review findings.
  *
  * Aim for `shortSummary` around 60 characters — anything longer truncates in
  * the status bar (and crowds the event log).
  */
// TODO: use the same Task class for simple & extended plans. Move the class to the parent `plan` package
case class Task(
    branchName: String,
    shortSummary: String,
    description: String
) derives JsonData

/** A list of tasks the agent should work through in order. Plans stored on disk
  * use a richer markdown-backed representation; see [[orca.plan.extended]] for
  * that.
  *
  * The "simple" variant fits in one LLM round-trip: the agent produces the
  * JSON; the runtime parses it; the flow iterates.
  */
case class SimplePlan(tasks: List[Task]) derives JsonData

object SimplePlan:
  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies
    * the picture; a planning failure is more useful as an explicit
    * `fail(...)` from the script.
    */
  given Announce[SimplePlan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.tasks.head.branchName}':"
      val body = plan.tasks.map(t => s"  - ${t.shortSummary}").mkString("\n")
      s"$header\n$body"
