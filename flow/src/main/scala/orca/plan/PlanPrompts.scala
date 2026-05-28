package orca.plan

import orca.util.PromptResource

/** Default prompt fragments for the planning helpers. Each `val` is a complete
  * instruction block that the helper appends to the user's request. Override by
  * passing a different string to the helper's `instructions` parameter — wrap
  * one of these defaults if you only want to extend the boilerplate:
  *
  * {{{
  * Plan.interactive.from(userPrompt, claude,
  *   instructions = PlanPrompts.Planning + "\n\nFocus on observability tasks first.")
  * }}}
  *
  * Source text lives in `src/main/resources/orca/plan/prompts/`.
  */
object PlanPrompts:

  /** Used by `Plan.interactive.*` and `Plan.autonomous.*` to brief the planner
    * agent. Without the opening clause agents tend to start editing files
    * during the planning turn — the implementation is the implementer's job.
    */
  val Planning: String =
    PromptResource.load("/orca/plan/prompts/planning.md")

  /** Used by `Plan.autonomous.assessThenPlan`. Asks the agent to first verify
    * the report against the repo, then either return a critique/rebuff/
    * follow-up question, or a plan in the usual shape. The agent gets
    * autonomous tool access (Read/Bash) — that's the point of the verification.
    */
  val AssessThenPlan: String =
    PromptResource.load("/orca/plan/prompts/assess-then-plan.md")

  /** Used by `Triage.interactive`. Structured-output instructions that pick out
    * the `NotABug` / `Untestable` / `Testable` variants via the underlying wire
    * fields.
    */
  val Triage: String =
    PromptResource.load("/orca/plan/prompts/triage.md")
