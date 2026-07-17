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

/** The planning-role agent (ADR 0020): resolved from settings (`planningAgent =
  * harness[:model]`), default claude. Reference it in `Plan.*` calls instead of
  * a concrete accessor so planning follows whichever backend the settings name.
  * A session minted from `planningAgent.session` threads because
  * [[FlowContext.PlanB]] pins the backend; see [[codingAgent]] for the
  * helper-authoring caveat shared by all three role accessors.
  */
def planningAgent(using ctx: FlowContext): Agent[ctx.PlanB] = ctx.planningAgent

/** The coding-role agent — the run's primary: implementer sessions, branch
  * naming, stack discovery, and default commit messages run here. Reference it
  * in a body instead of a concrete accessor (`claude`/`codex`) so the flow
  * follows whichever backend `codingAgent = harness[:model]` in settings names.
  * A session from `codingAgent.session` threads into `session.run` and the
  * reviewers because [[FlowContext.CodeB]] pins the backend.
  *
  * Two ways to drive a model in a flow: a role accessor
  * (`codingAgent`/`planningAgent`/`reviewAgent`) is backend-agnostic — settings
  * choose the harness, and its session threads because the role type pins the
  * backend. A concrete accessor + tier (`claude.opus`, `codex.mini`,
  * `opencode.openaiLuna`) names a specific backend/tier — for interactive
  * planning or a one-off cheap call. The tier accessors (`.opus`/`.sonnet`/…)
  * live on the concrete types, not on the agnostic role accessors, so name the
  * model/tier **first**, then any constraints (`claude.opus.withReadOnly`, not
  * `claude.withReadOnly.opus`). Don't mix the two for one session: a
  * `SessionId` is backend-typed, so a session minted from `claude` won't thread
  * through `codingAgent` when settings name a different backend.
  *
  * See [[orca.FlowContext.CodeB]] for the helper-authoring caveat that the
  * path-dependent `Agent[ctx.CodeB]` (and `ctx.PlanB` / `ctx.ReviewB`) carries
  * once code is factored into a helper *function*, shared by all three role
  * accessors.
  */
def codingAgent(using ctx: FlowContext): Agent[ctx.CodeB] = ctx.codingAgent

/** The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker and
  * the lint summariser default to its tiers. See [[codingAgent]] for the
  * helper-authoring caveat shared by all three role accessors.
  */
def reviewAgent(using ctx: FlowContext): Agent[ctx.ReviewB] = ctx.reviewAgent
