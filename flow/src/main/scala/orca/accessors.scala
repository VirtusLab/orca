package orca

import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.agents.{
  Agent,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
}

// Top-level accessors that resolve against the ambient FlowContext.
// Flow scripts can write `git.checkout("main")` or `claude.ask(...)`
// instead of `summon[FlowContext].git.checkout(...)`.

def claude(using ctx: FlowContext): ClaudeAgent = ctx.claude
def codex(using ctx: FlowContext): CodexAgent = ctx.codex
def opencode(using ctx: FlowContext): OpencodeAgent = ctx.opencode
def pi(using ctx: FlowContext): PiAgent = ctx.pi
def gemini(using ctx: FlowContext): GeminiAgent = ctx.gemini
def git(using ctx: FlowContext): GitTool = ctx.git
def gh(using ctx: FlowContext): GitHubTool = ctx.gh
def fs(using ctx: FlowContext): FsTool = ctx.fs
def userPrompt(using ctx: FlowContext): String = ctx.userPrompt

/** The leading coding agent — the harness chosen by `flow`'s selector
  * (`_.claude`, `_.codex`, …). Just another accessor over the ambient
  * `FlowContext`, like `claude`/`git`.
  *
  * Two ways to drive a model in a flow:
  *   - **`agent`** — the leading agent, backend-agnostic. Use it for the flow's
  *     planning / implementing / reviewing and its session
  *     (`agent.session(name, seed)` → `FlowSession[ctx.LeadB]`, driven with
  *     `session.run(...)`): switch the selector and the whole flow follows. A
  *     session threads because `agent` is `Agent[ctx.LeadB]` (pinned to the
  *     backend), not an erased `Agent[?]`.
  *   - **a concrete accessor + model** — `claude.opus`, `claude.sonnet`,
  *     `codex.mini`, `opencode.openaiLuna`. Use these for a specific backend or
  *     tier, or for interactive planning (`Plan.interactive` needs a concrete
  *     backend). The tier accessors (`.opus`/`.sonnet`/…) live on the concrete
  *     types, NOT on the agnostic `agent`, so `agent.opus` won't compile —
  *     that's the cue to name the backend. Name the model/tier **first**, then
  *     any constraints — `claude.opus.withReadOnly`, not
  *     `claude.withReadOnly.opus` — since only the tier accessors return the
  *     concrete agent that `.opus`/`.sonnet` hang off.
  *
  * Don't mix the two for one session: a `SessionId` is backend-typed, so a
  * session minted from `claude` won't thread through `agent` once the selector
  * is something else.
  */
def agent(using ctx: FlowContext): Agent[ctx.LeadB] = ctx.agent

/** The planning-role agent (ADR 0020): resolved from settings (`planningAgent =
  * <harness>[:<model>]`), default claude. Reference it in `Plan.*` calls
  * instead of a concrete accessor so planning follows whichever backend the
  * settings name. A session minted from `planningAgent.session` threads because
  * [[FlowContext.PlanB]] pins the backend; see [[codingAgent]] for the
  * helper-authoring caveat shared by all three role accessors.
  */
def planningAgent(using ctx: FlowContext): Agent[ctx.PlanB] = ctx.planningAgent

/** The coding-role agent — the run's primary: implementer sessions, branch
  * naming, stack discovery, and default commit messages run here. Reference it
  * in a body instead of a concrete accessor (`claude`/`codex`) so the flow
  * follows whichever backend `codingAgent = <harness>[:<model>]` in settings
  * names. A session from `codingAgent.session` threads into `session.run` and
  * the reviewers because [[FlowContext.CodeB]] pins the backend.
  *
  * '''Helper authoring:''' the path-dependent `Agent[ctx.CodeB]` (the same
  * holds for `ctx.PlanB` / `ctx.ReviewB`) is convenient in a straight-line
  * `flow(...)` body, where every reference resolves against the same `using
  * FlowContext` in scope — but it stops working the moment you factor code into
  * a helper *function*. `ctx1.CodeB` and `ctx2.CodeB` from two different `using
  * FlowContext` parameters are different types to the compiler even when
  * they're the same backend at runtime, so a helper that takes
  * `Agent[ctx.CodeB]` in one parameter and tries to combine it with
  * `SessionId[ctx.CodeB]` from another can't unify them. Two ways out, both
  * used by the library's own helpers:
  *
  *   - Take an explicit `[B <: BackendTag]` type parameter and type the
  *     helper's own parameters against it — `B` is then a genuine type variable
  *     the caller instantiates once, not a path into someone else's context.
  *     See [[orca.review.reviewAndFixLoop]]`(coderSession: FlowSession[B],
  *     ...)`, whose single [[orca.FlowSession]] bundles the agent and its
  *     session so `B` is pinned once at the call site.
  *   - Bundle the agent's session with its result as a single
  *     [[orca.plan.Sessioned]]`[B, A]` value, so callers pass one thing instead
  *     of two that have to agree on `B`. See `Plan.autonomous.*` /
  *     `Plan.interactive.*`.
  */
def codingAgent(using ctx: FlowContext): Agent[ctx.CodeB] = ctx.codingAgent

/** The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker and
  * the lint summariser default to its tiers. See [[codingAgent]] for the
  * helper-authoring caveat shared by all three role accessors.
  */
def reviewAgent(using ctx: FlowContext): Agent[ctx.ReviewB] = ctx.reviewAgent
