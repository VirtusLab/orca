package orca

import orca.progress.ProgressStore
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

/** Build a stable, per-run HTML comment marker for use with
  * [[GitHubTool.upsertComment]]. The marker is an HTML comment invisible in the
  * rendered GitHub UI but detectable in the raw body, enabling a re-run to find
  * and update its own prior comment instead of duplicating it.
  *
  * `userPrompt` is hashed so two different flow runs for different prompts
  * produce distinct markers even with the same `purpose`. `purpose` further
  * namespaces the marker within a single run (e.g. `"reject"`, `"triage"`).
  *
  * Example: `orcaCommentMarker(userPrompt, "reject")` produces a single-line
  * marker: `<!-- orca:a1b2c3d4e5f6:reject -->`
  */
def orcaCommentMarker(userPrompt: String, purpose: String): String =
  s"<!-- orca:${ProgressStore.hashPrompt(userPrompt)}:$purpose -->"
