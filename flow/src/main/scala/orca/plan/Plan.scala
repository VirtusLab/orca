package orca.plan

import orca.{FlowContext, InStage, OrcaFlowException}
import orca.agents.{Announce, BackendTag, JsonData, Agent, given}

import scala.annotation.unused

/** A development plan: an ordered list of [[Task]]s the agent works through on
  * a single branch, plus a `brief` — a concise codebase briefing the
  * implementing agents rely on. The brief is always present: it is part of the
  * planner's structured output, and feeds the implementer session seed (ADR
  * 0018 §2.6).
  *
  * `epicId` is a kebab-case identifier for the plan itself (it heads the
  * markdown render), NOT the git branch name: the flow derives and announces
  * its own branch at setup via [[orca.BranchNamingStrategy]], so the two can
  * differ.
  *
  * ==Planning grid==
  *
  * The entry points form an orthogonal `mode × operation` grid:
  *
  *   - **mode** — [[autonomous]] (single agentic turn, read-only, no human) or
  *     [[interactive]] (a conversation the agent can drive via `ask_user`).
  *   - **operation** — `from` (produce a [[Plan]] directly), `assessThenPlan`
  *     (skeptically assess first, returning a [[Verdict]] that either proceeds
  *     with a plan or rejects), or `triage` (classify a bug report into a
  *     [[Triage]] verdict).
  *
  * Every cell returns a [[Sessioned]] — the result plus the agent session that
  * produced it. A `Sessioned[B, Plan]` can be continued read-only into
  * [[Sessioned.reviewed]] (self-critique), or discarded for a fresh implementer
  * session.
  *
  * As a single case class it is a valid stage result (ADR 0018 §2.3) — the
  * stage log, not a plan file, is what resume reads.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task],
    brief: String
) derives JsonData:

  /** Prompt for `task`: its description, with the shared brief prepended when
    * present (an empty brief yields the description verbatim).
    */
  def taskPrompt(task: Task): String =
    if brief.isEmpty then task.description
    else s"$brief\n\n---\n\n${task.description}"

object Plan:

  /** Autonomous planning — a single agentic turn, no human in the loop. The
    * agent runs `NetworkOnly`: it can verify claims via Read/Grep and read-only
    * network (issues/PRs/web) but can't edit during the planning turn (see
    * [[autonomousResult]] for the per-backend guarantee).
    *
    * Each operation returns a [[Sessioned]]: the read-only planning session is
    * still resumable by a later writable call, so the caller can continue it
    * into implementation or discard it for a fresh session.
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

  /** Interactive planning — opens a conversation the user can drive (clarifying
    * questions, refinements) before the agent produces the result. Not
    * read-only: plan mode would disable the `ask_user` MCP tool, so the agent
    * runs with normal permissions; the prompt asks it not to edit, and the user
    * sees any violation. Use [[autonomous]] when no mid-session questions are
    * needed.
    *
    * Each operation returns a [[Sessioned]] so the conversation can carry into
    * implementation.
    */
  object interactive:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag](
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
    def assessThenPlan[B <: BackendTag](
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
    def triage[B <: BackendTag](
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
    * operation.
    *
    * Runs `NetworkOnly`: reads plus read-only network, so the planner can fetch
    * an issue/PR and verify external claims. Edits stay blocked (hard on
    * claude/gemini/opencode; prompt-only on pi/codex). Reviewers and the
    * post-planning `reviewed` turn use plain `withReadOnly` instead — no
    * network, hard no-edit everywhere.
    */
  private def autonomousResult[B <: BackendTag, O: JsonData: Announce, A](
      agent: Agent[B],
      input: String,
      instructions: String
  )(convert: O => A)(using
      @unused ctx: FlowContext,
      ev: InStage
  ): Sessioned[B, A] =
    // The planning turn runs on the restricted (NetworkOnly) sibling, but the
    // chat handed out is bound to the BASE agent, so a continuation regains the
    // caller's full capability — the restriction stays per-turn.
    val planningChat = agent.withNetworkOnly.chat()
    val raw = planningChat
      .resultAs[O]
      .autonomous
      .run(withInstructions(input, instructions))
    Sessioned(agent.chat(planningChat.id), convert(raw))

  /** Interactive counterpart to [[autonomousResult]] — no per-turn restriction
    * (interactive planning runs with normal permissions, see [[interactive]]),
    * so the chat is minted on `agent` directly.
    */
  private def interactiveResult[
      B <: BackendTag,
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
    val chat = agent.chat()
    val raw = chat
      .resultAs[O]
      .interactive
      .run(withInstructions(input, instructions))
    Sessioned(chat, convert(raw))

  // `reviewed` resumes the planning session read-only, reusing the planner's
  // exploration. Defined here to keep it in the implicit scope of
  // `Sessioned[B, Plan]`.

  extension [B <: BackendTag](sp: Sessioned[B, Plan])
    /** Resume the planning conversation for a critical self-review, returning
      * the improved plan (brief included) paired with the (same) chat. The
      * review turn runs read-only on `agent`; the handed-back chat keeps the
      * original binding.
      */
    def reviewed(
        agent: Agent[B],
        instructions: String = PlanPrompts.Review
    )(using @unused ctx: FlowContext, ev: InStage): Sessioned[B, Plan] =
      val improved = agent.withReadOnly
        .chat(sp.chat.id)
        .resultAs[Plan]
        .autonomous
        .run(s"$instructions\n\n${render(sp.value)}")
      Sessioned(sp.chat, improved)

  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[Plan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      // No branch name here: `epicId` is the plan's own identifier, not the git
      // branch (derived and announced separately at setup).
      val header = s"Planned ${plan.tasks.size} task$plural:"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"

  /** Render a plan to markdown (tasks as plain bullets, the brief as a trailing
    * `## Brief` section). Used by [[Sessioned.reviewed]] to feed the plan back
    * into the self-review prompt, and usable as a human-readable summary. Never
    * parsed back — the stage log is the sole resume mechanism (ADR 0018 §2.8).
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
