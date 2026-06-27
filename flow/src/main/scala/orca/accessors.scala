package orca

import orca.progress.ProgressStore
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.agents.{
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  Agent,
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
  * and the reviewers, because the ambient [[Lead]] carrier pins the backend
  * type (a bare `Agent[?]` can't carry a session across calls). Available
  * inside every `flow(...)` body, which supplies the [[Lead]] given.
  */
def agent(using l: Lead): Agent[l.B] = l.tool

/** Carrier for a flow's leading agent. Supplied as a single stable given inside
  * each `flow(...)` body; its `B` type member pins the lead's backend so the
  * [[agent]] accessor can hand back a concretely-typed `Agent[B]` (and thus a
  * threadable session) even though the runtime only holds the lead erased as
  * `Agent[?]`. Users don't construct or name this — they read the lead via
  * [[agent]]; helper defs that call `agent` declare `(using Lead)`.
  */
sealed trait Lead:
  type B <: BackendTag
  def tool: Agent[B]

object Lead:
  def apply[B0 <: BackendTag](t: Agent[B0]): Lead { type B = B0 } =
    new Lead:
      type B = B0
      def tool: Agent[B0] = t

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
