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

/** The leading coding agent for this flow — the harness chosen by `flow`'s
  * `agent` selector (`_.claude`, `_.codex`, …). Reference it in a flow body
  * instead of a concrete backend accessor (`claude`, `codex`) so the body is
  * backend-agnostic: switch the selector and the whole flow follows. A session
  * obtained via `agent.session(...)` threads back into `agent.runSeeded(...)`
  * and the reviewers, because the result type is `Agent[ctx.LeadB]` — pinned to
  * the backend by [[FlowContext.LeadB]], not an erased `Agent[?]`. Just another
  * accessor over the ambient `FlowContext`, exactly like `claude`/`git`.
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
