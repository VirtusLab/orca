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
    * agent. The opening keeps the model from sliding into editing files
    * mid-plan — without it agents frequently start writing code during the
    * planning turn, which is the implementer's job. The trailing principles
    * direct the breakdown toward independently-shippable atomic tasks rather
    * than a "scaffolding then test" or "all impl then all tests" split.
    */
  val Planning: String =
    """Your job in this turn is to produce a development plan only — a
      |list of tasks broken down to a useful granularity. Do NOT edit
      |any files, do NOT write any code, and do NOT run build / test
      |commands. The plan is an outline; the implementation happens in
      |a separate later turn, task by task.
      |
      |Plan as a senior engineer reviewing the work would expect to see
      |it, following these principles:
      |
      |  - Each task is a single shippable change. If a behaviour change
      |    needs a test, the test belongs in the same task as the change
      |    — not as a sibling task that runs after the implementation.
      |  - Order tasks so each one stands on its own. No task should
      |    depend on a later task's output to be reviewable, runnable, or
      |    to leave the codebase in a working state.
      |  - Keep tasks small enough that an implementer can finish in one
      |    focused turn — roughly: one test plus the code change it
      |    covers, plus the trivial wiring those need.""".stripMargin
