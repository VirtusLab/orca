package orca

import orca.progress.ProgressStore
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.llm.{ClaudeTool, CodexTool, GeminiTool, OpencodeTool, PiTool}

// Top-level accessors that resolve against the ambient FlowContext.
// Flow scripts can write `git.checkout("main")` or `claude.ask(...)`
// instead of `summon[FlowContext].git.checkout(...)`.

def claude(using ctx: FlowContext): ClaudeTool = ctx.claude
def codex(using ctx: FlowContext): CodexTool = ctx.codex
def opencode(using ctx: FlowContext): OpencodeTool = ctx.opencode
def pi(using ctx: FlowContext): PiTool = ctx.pi
def gemini(using ctx: FlowContext): GeminiTool = ctx.gemini
def git(using ctx: FlowContext): GitTool = ctx.git
def gh(using ctx: FlowContext): GitHubTool = ctx.gh
def fs(using ctx: FlowContext): FsTool = ctx.fs
def userPrompt(using ctx: FlowContext): String = ctx.userPrompt

/** Build a stable, per-run HTML comment marker for use with
  * [[GitHubTool.upsertComment]]. The marker is an HTML comment invisible in the
  * rendered GitHub UI but detectable in the raw body, enabling a re-run to find
  * and update its own prior comment instead of duplicating it (R24).
  *
  * `userPrompt` is hashed so two different flow runs for different prompts
  * produce distinct markers even with the same `purpose`. `purpose` further
  * namespaces the marker within a single run (e.g. `"reject"`, `"triage"`).
  *
  * Example: `orcaCommentMarker(userPrompt, "reject")` → `<!--
  * orca:a1b2c3d4e5f6:reject -->`
  */
def orcaCommentMarker(userPrompt: String, purpose: String): String =
  s"<!-- orca:${ProgressStore.hashPrompt(userPrompt)}:$purpose -->"
