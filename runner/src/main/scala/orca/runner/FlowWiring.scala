package orca.runner

import orca.agents.{
  ClaudeAgent,
  CodexAgent,
  DefaultPrompts,
  GeminiAgent,
  OpencodeAgent,
  PiAgent,
  Prompts
}
import orca.tools.{FsTool, GitHubTool, GitTool}
import orca.tools.opencode.OpencodeLauncher

/** The per-run tool/agent override bundle `flow(...)` collects from its named
  * arguments. One value tunnels through `runFlow` → `withDefaults` instead of
  * ten positional parameters repeated at each layer; adding a backend is one
  * field here plus one `getOrElse` in `withDefaults`.
  */
private[orca] case class FlowWiring(
    claude: Option[ClaudeAgent] = None,
    codex: Option[CodexAgent] = None,
    opencode: Option[OpencodeAgent] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[PiAgent] = None,
    gemini: Option[GeminiAgent] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts
)
