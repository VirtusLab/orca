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
import orca.tools.opencode.OpencodeLauncher
import ox.Ox

/** The per-run tool/agent override bundle `flow(...)` collects from its named
  * arguments. One value tunnels through `runFlow` → `withDefaults` instead of
  * ten positional parameters repeated at each layer; adding a backend is one
  * field here plus one arm in `withDefaults`.
  *
  * Agent overrides are `AgentWiring => Agent` factories, not prebuilt agents:
  * the runtime hands the factory the run's [[AgentWiring]] (event sink,
  * interaction, workDir, prompts) so a user agent is wired into the same
  * dispatcher as the defaults — costs/steps reach the tracker and terminal
  * (complexity-review 7.8). A caller with a prebuilt agent writes `Some(_ =>
  * myAgent)`.
  */
private[orca] case class FlowWiring(
    claude: Option[AgentWiring => ClaudeAgent] = None,
    codex: Option[AgentWiring => CodexAgent] = None,
    // `Ox ?=>` result (unlike the other, plain agent factories): the opencode
    // backend pins a `serve` process + drain forks to the run scope at
    // construction, so this factory is applied inside `withDefaults`' Ox scope,
    // not at the `flow(...)` argument site. See `flow`'s param scaladoc.
    opencode: Option[AgentWiring => Ox ?=> OpencodeAgent] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[AgentWiring => PiAgent] = None,
    gemini: Option[AgentWiring => GeminiAgent] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts
)
