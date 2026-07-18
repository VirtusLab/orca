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
import orca.backend.AgentWiring
import orca.tools.{FsTool, GitHubTool, GitTool}
import ox.Ox

/** The per-run tool/agent override bundle `flow(...)` collects from its named
  * arguments. One value tunnels through `runFlow` → `WiredAgents.build` instead
  * of ten positional parameters repeated at each layer; adding a backend is one
  * field here plus one arm in `WiredAgents.build`.
  *
  * Agent overrides are `AgentWiring => Ox ?=> Agent` factories, not prebuilt
  * agents: the runtime hands the factory the run's [[AgentWiring]] (event sink,
  * interaction, workDir, prompts) so a user agent is wired into the same
  * dispatcher as the defaults — costs/steps reach the tracker and terminal. A
  * caller with a prebuilt agent writes `Some(_ => myAgent)`. All five fields
  * share the `Ox ?=>` shape even though only opencode's default factory needs
  * it (it pins a `serve` process + drain forks to the run scope, so its factory
  * is applied inside `WiredAgents.build`'s Ox scope); a plain `AgentWiring =>
  * Agent` lambda literal auto-adapts to the shape.
  */
private[orca] case class FlowWiring(
    claude: Option[AgentWiring => Ox ?=> ClaudeAgent] = None,
    codex: Option[AgentWiring => Ox ?=> CodexAgent] = None,
    opencode: Option[AgentWiring => Ox ?=> OpencodeAgent] = None,
    pi: Option[AgentWiring => Ox ?=> PiAgent] = None,
    gemini: Option[AgentWiring => Ox ?=> GeminiAgent] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts
)
