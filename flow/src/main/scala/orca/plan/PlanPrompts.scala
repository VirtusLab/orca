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

  /** Used by `Plan.{autonomous,interactive}.triage`: assess the request, then
    * pick `Reject` / `TestableBug` / `UntestableBug` / `Change`.
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

  /** Used by `Plan.{autonomous,interactive}.roadmap`: split the request into
    * ordered epics, with a shared brief.
    */
  val Roadmap: String = PromptResource.load("/orca/plan/prompts/roadmap.md")

  /** Used by `WithChat[Roadmap].reviewed` for the critic; the request and the
    * roadmap are appended after it.
    */
  val RoadmapCritique: String =
    PromptResource.load("/orca/plan/prompts/roadmap-critique.md")

  /** Used by `WithChat[Roadmap].reviewed` for the planner; the critique and the
    * roadmap are appended after it.
    */
  val RoadmapRevise: String =
    PromptResource.load("/orca/plan/prompts/roadmap-revise.md")

  /** Used by [[reproduceBug]]: write the failing test. The test path is
    * appended after it.
    */
  val Reproduce: String =
    PromptResource.load("/orca/plan/prompts/reproduce.md")

  /** Used by [[reproduceBug]] when the check rejects the first test. */
  val ReproduceRetry: String =
    PromptResource.load("/orca/plan/prompts/reproduce-retry.md")

  /** Used by [[reproduceBug]] to judge the test; the test path and the request
    * are appended after it.
    */
  val ReproductionCheck: String =
    PromptResource.load("/orca/plan/prompts/reproduction-check.md")
