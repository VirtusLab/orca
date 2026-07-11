package orca.plan

import orca.{
  FlowContext,
  FlowControl,
  FlowSession,
  InStage,
  OrcaFlowException,
  session
}
import orca.agents.{Announce, BackendTag, CanAskUser, JsonData, Agent, given}

import scala.annotation.unused

/** A development plan: an ordered list of [[Task]]s the agent will work
  * through, all on a single branch, plus a `brief` — a concise codebase
  * briefing the implementing agents rely on so they don't have to rediscover
  * the layout.
  *
  * `epicId` is a kebab-case identifier for the plan itself (it heads the
  * markdown render). It is NOT the git branch name: the flow derives and
  * announces its own branch at setup via [[orca.BranchNamingStrategy]], so the
  * two can differ.
  *
  * The brief is always present: it is produced as part of the planner's
  * structured output, not a separate turn, and feeds the implementer session
  * seed (ADR 0018 §2.6).
  *
  * ==Planning grid==
  *
  * The planning entry points form a `mode × operation` grid. Mode and operation
  * are orthogonal — every combination is valid:
  *
  *   - **mode** — [[autonomous]] (single agentic turn, read-only, no human) or
  *     [[interactive]] (a conversation the agent can drive via `ask_user`).
  *     Chosen by the nested object so it's visible at the call site, mirroring
  *     `claude.autonomous.run` / `claude.resultAs[O].interactive.run`.
  *   - **operation** — `from` (produce a [[Plan]] directly), `assessThenPlan`
  *     (skeptically assess first, returning a [[Verdict]] that either proceeds
  *     with a plan or rejects), or `triage` (classify a bug report into a
  *     [[Triage]] verdict).
  *
  * Every cell returns a [[Sessioned]] — the result plus the agent session that
  * produced it. From a `Sessioned[B, Plan]` the same session can be continued
  * read-only into [[Sessioned.reviewed]] (self-critique), or discarded for a
  * fresh implementer session.
  *
  * `derives JsonData` so the structured-output path works directly: the helper
  * methods consume Orca's auto-generated JSON schema; no caller-side
  * serialization is needed. As a single case class it is also a valid stage
  * result (ADR 0018 §2.3) — the stage log, not a plan file, is what resume
  * reads.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task],
    brief: String
) derives JsonData:

  /** Prompt for `task`: its description, with the shared brief prepended when
    * present. An empty brief yields the description verbatim — no stray
    * separator.
    */
  def taskPrompt(task: Task): String =
    if brief.isEmpty then task.description
    else s"$brief\n\n---\n\n${task.description}"

object Plan:

  /** Autonomous planning — a single agentic turn, no human in the loop. The
    * agent runs `NetworkOnly` (`.withNetworkOnly`): it can verify claims via
    * Read/Grep and read-only network (issues/PRs/web) but can't edit during the
    * planning turn (see [[autonomousResult]] for the per-backend guarantee).
    * Sibling of [[interactive]]; the choice between the two is visible at the
    * call site (`Plan.autonomous.from(...)` vs `Plan.interactive.from(...)`),
    * mirroring `Agent`'s own `autonomous` / `interactive` split.
    *
    * Each operation returns a [[Sessioned]]: the read-only planning turn's
    * session is still resumable by a later writable call, so the caller can
    * continue it into implementation or discard it for a fresh session.
    *
    * No `CanAskUser` bound (unlike [[interactive]]): an autonomous turn never
    * calls `ask_user`, so it works with any backend.
    */
  object autonomous:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag](
        userPrompt: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext, InStage): Sessioned[B, Plan] =
      autonomousResult[B, Plan, Plan](agent, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt` (typically a bug/feature report) and
      * either proceed with a plan or reject with a [[Verdict.Rejection]] the
      * caller surfaces to whoever filed it.
      */
    def assessThenPlan[B <: BackendTag](
        userPrompt: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext, InStage): Sessioned[B, Verdict[Plan]] =
      autonomousResult[B, AssessedPlan, Verdict[Plan]](
        agent,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict (not-a-bug / untestable
      * / testable).
      */
    def triage[B <: BackendTag](
        report: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext, InStage): Sessioned[B, Triage] =
      autonomousResult[B, BugTriage, Triage](agent, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

  /** Interactive planning — the LLM call opens a conversation the user can
    * drive (clarifying questions, refinements) before the agent produces the
    * result. Not read-only: claude's plan mode would disable the `ask_user` MCP
    * tool, so the agent runs with normal permissions; the prompt asks it not to
    * edit, and the user sees any violation.
    *
    * The `B: CanAskUser` constraint means these compile only with backends that
    * can host an `ask_user` tool — claude and codex (both via the shared
    * `AskUserMcpServer`) and pi (via Orca's temporary ask-user extension). A
    * future stdin-only backend would fail this at compile time. Use
    * [[autonomous]] when no mid-session questions are needed.
    *
    * Each operation returns a [[Sessioned]] so the conversation can carry into
    * implementation (e.g. a triage agent's exploration informs the fix).
    */
  object interactive:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag: CanAskUser](
        userPrompt: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext, InStage): Sessioned[B, Plan] =
      interactiveResult[B, Plan, Plan](agent, userPrompt, instructions)(
        identity
      )

    /** Skeptically assess `userPrompt`, but able to ask the reporter clarifying
      * questions mid-turn rather than only rejecting with a
      * [[Verdict.RejectionKind.Question]].
      */
    def assessThenPlan[B <: BackendTag: CanAskUser](
        userPrompt: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext, InStage): Sessioned[B, Verdict[Plan]] =
      interactiveResult[B, AssessedPlan, Verdict[Plan]](
        agent,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict, able to ask the
      * reporter clarifying questions before deciding.
      */
    def triage[B <: BackendTag: CanAskUser](
        report: String,
        agent: Agent[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext, InStage): Sessioned[B, Triage] =
      interactiveResult[B, BugTriage, Triage](agent, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

  /** Append the operation's instruction block to the caller's input. */
  private def withInstructions(input: String, instructions: String): String =
    s"$input\n\n$instructions"

  /** Surface a structured-contract violation (a `toVerdict` / `toTriage`
    * `Left`) as a flow failure. The decode succeeded but the field combination
    * was incoherent past the retry loop, so it's a system-level failure.
    */
  private def getOrFail[A](result: Either[String, A]): A =
    result.fold(msg => throw OrcaFlowException(msg), identity)

  /** Run one autonomous turn producing wire type `O`, convert it to the public
    * result `A`, and pair it with the session. Shared by every `autonomous.*`
    * operation (`from`, `assessThenPlan`, `triage`).
    *
    * Runs `NetworkOnly`: reads plus read-only network, so the planner can fetch
    * an issue/PR it was pointed at and verify external claims. Edits stay
    * blocked (hard on claude/gemini/opencode; prompt-only on pi/codex — the
    * planning prompts forbid edits). Reviewers and the post-planning `reviewed`
    * turn use plain `withReadOnly` instead — no network, hard no-edit
    * everywhere.
    */
  private def autonomousResult[B <: BackendTag, O: JsonData: Announce, A](
      agent: Agent[B],
      input: String,
      instructions: String
  )(convert: O => A)(using
      @unused ctx: FlowContext,
      ev: InStage
  ): Sessioned[B, A] =
    val (sessionId, raw) = agent.withNetworkOnly
      .resultAs[O]
      .autonomous
      .run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  /** Interactive counterpart to [[autonomousResult]]. */
  private def interactiveResult[
      B <: BackendTag: CanAskUser,
      O: JsonData: Announce,
      A
  ](
      agent: Agent[B],
      input: String,
      instructions: String
  )(convert: O => A)(using
      @unused ctx: FlowContext,
      ev: InStage
  ): Sessioned[B, A] =
    val (sessionId, raw) =
      agent.resultAs[O].interactive.run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  // == Post-planning step on a produced plan ==
  //
  // `reviewed` resumes the planning session read-only, reusing the planner's
  // exploration. Defined here so it's in the implicit scope of
  // `Sessioned[B, Plan]` — no extra import needed.

  extension [B <: BackendTag](sp: Sessioned[B, Plan])
    /** Resume the planning session for a critical self-review, returning the
      * improved plan (brief included) paired with the (same) session.
      */
    def reviewed(
        agent: Agent[B],
        instructions: String = PlanPrompts.Review
    )(using @unused ctx: FlowContext, ev: InStage): Sessioned[B, Plan] =
      val (sessionId, improved) = agent.withReadOnly
        .resultAs[Plan]
        .autonomous
        .run(s"$instructions\n\n${render(sp.value)}", session = sp.sessionId)
      Sessioned(sessionId, improved)

  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[Plan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      // No branch name here: `epicId` is the plan's own identifier, not the git
      // branch (which the flow derives and announces separately at setup). The
      // two can differ, so naming a branch here would be misleading on resume.
      val header = s"Planned ${plan.tasks.size} task$plural:"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"

  /** Render a plan to markdown (tasks as plain bullets, the brief as a trailing
    * `## Brief` section). Used by [[Sessioned.reviewed]] to feed the plan back
    * into the self-review prompt, and equally usable as a human-readable
    * summary. It is **never parsed back**: the stage log is the sole resume
    * mechanism (ADR 0018 §2.8), so there is no inverse parser to keep in sync.
    */
  def render(plan: Plan): String =
    val base = renderPlan(plan)
    if plan.brief.trim.isEmpty then base
    else s"$base\n## Brief\n\n${plan.brief.stripLineEnd}\n"

  private def renderPlan(plan: Plan): String =
    val header = s"# Plan: ${plan.epicId}\n"
    val descriptionBlock =
      if plan.description.trim.isEmpty then ""
      else s"\n${plan.description.stripLineEnd}\n"
    val body = plan.tasks
      .map: t =>
        s"\n## Task: ${t.title}\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + descriptionBlock + body
