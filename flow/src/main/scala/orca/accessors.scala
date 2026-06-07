package orca

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
