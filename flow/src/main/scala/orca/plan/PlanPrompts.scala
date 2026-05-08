package orca.plan

/** Default prompt fragments for the planning helpers. Each `val` is a complete
  * instruction block that the helper appends to the user's request. Override by
  * passing a different string to the helper's `instructions` parameter — wrap
  * one of these defaults if you only want to extend the boilerplate:
  *
  * {{{
  * Plan.interactive.from(userPrompt, claude,
  *   instructions = PlanPrompts.Planning + "\n\nFocus on observability tasks first.")
  * }}}
  */
object PlanPrompts:

  /** Used by `Plan.interactive.*` and `Plan.autonomous.*` to brief the planner
    * agent. Without the opening clause agents tend to start editing files
    * during the planning turn — the implementation is the implementer's job.
    */
  val Planning: String =
    """Your job in this turn is to produce a development plan only — an
      |epic description plus a list of tasks broken down to a useful
      |granularity. Do NOT edit any files, do NOT write any code, and do
      |NOT run build / test commands. The plan is an outline; the
      |implementation happens in a separate later turn, task by task.
      |
      |The `description` is a 1-3 paragraph summary of what the epic
      |achieves and why — the context an implementer would need before
      |touching any single task. Keep it concrete and goal-oriented; do
      |not restate the task list.
      |
      |Each task should be: atomic (impl + tests together), independent
      |of later tasks, shippable on its own, and small enough for one
      |focused implementer turn.""".stripMargin
