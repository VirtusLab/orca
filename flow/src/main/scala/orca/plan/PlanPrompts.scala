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

  /** Briefs the planner agent for `Plan.{autonomous,interactive}.from`. The
    * opening clause keeps agents from editing files during the planning turn;
    * also asks the planner to fill the `brief` field with a codebase briefing
    * for the implementing agents.
    */
  val Planning: String =
    PromptResource.load("/orca/plan/prompts/planning.md")

  /** Used by `Plan.{autonomous,interactive}.assessThenPlan`. Asks the agent to
    * first verify the report against the repo, then either return a
    * critique/rebuff/follow-up question, or a plan in the usual shape. The
    * agent gets tool access (Read/Bash) — that's the point of the verification.
    */
  val AssessThenPlan: String =
    PromptResource.load("/orca/plan/prompts/assess-then-plan.md")

  /** Used by `Plan.{autonomous,interactive}.triage`. Structured-output
    * instructions that pick out the `NotABug` / `Untestable` / `Testable`
    * variants via the underlying wire fields.
    */
  val Triage: String =
    PromptResource.load("/orca/plan/prompts/triage.md")

  /** Used by `WithChat[Plan].reviewed` for the critic, which has not seen the
    * planning conversation. The request and the plan are appended after this
    * block; the critic returns free-text findings.
    */
  val Critique: String =
    PromptResource.load("/orca/plan/prompts/critique.md")

  /** Used by `WithChat[Plan].reviewed` for the planner. The critique and the
    * plan are appended after this block; the planner returns an improved plan,
    * brief included.
    */
  val Revise: String =
    PromptResource.load("/orca/plan/prompts/revise.md")
