package orca.plan

import orca.{Announce, JsonData, given}

/** A list of tasks the agent should work through in order, all on a single
  * branch named by `epicId`. Plans stored on disk use the richer markdown-
  * backed [[ExtendedPlan]].
  *
  * The "simple" variant fits in one LLM round-trip: the agent produces the
  * JSON; the runtime parses it; the flow iterates. `epicId` is a kebab-case
  * identifier (lowercase, no spaces) — it doubles as the git branch name.
  */
case class SimplePlan(epicId: String, tasks: List[Task]) derives JsonData

object SimplePlan:
  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[SimplePlan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.epicId}':"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"
