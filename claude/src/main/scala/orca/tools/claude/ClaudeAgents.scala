package orca.tools.claude

import orca.agents.{Agent, AgentConfig, ClaudeAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** The default claude agent and the Claude-specific builders. Bare `claude`
  * runs Opus with the 1M-token context window (the coder); the tier accessors
  * pin a specific one, e.g. a cheap fast one-shot with
  * `claude.haiku.run("summarize this")`.
  */
object ClaudeAgents:

  /** The default claude agent for a run: Opus-1M lead model, standard config.
    * The backend's `workDir` is the run's `workDir` so a resumed worktree flow
    * probes the same directory the transcript lands under (see
    * [[ClaudeBackend.workDir]]).
    */
  def default(wiring: AgentWiring): ClaudeAgent =
    Agent(
      backend = new ClaudeBackend(OsProcCliRunner, workDir = wiring.workDir),
      config = AgentConfig(model = Some(ClaudeModels.Opus1M)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  extension (agent: ClaudeAgent)
    def haiku: ClaudeAgent = agent.withModel(ClaudeModels.Haiku)
    def sonnet: ClaudeAgent = agent.withModel(ClaudeModels.Sonnet)
    def opus: ClaudeAgent = agent.withModel(ClaudeModels.Opus1M)
    def fable: ClaudeAgent = agent.withModel(ClaudeModels.Fable)

    /** Set the network tools added to the read-only `--tools` allowlist on
      * [[orca.agents.ToolSet.NetworkOnly]] turns, replacing the default
      * `WebFetch`/`WebSearch`. Bare claude tool names, e.g. `WebFetch`; refuses
      * anything else, and write-capable builtins such as `Bash`. Pass it before
      * handing the agent to a planning helper:
      * `claude.opus.withNetworkTools(Seq("WebFetch"))`.
      */
    def withNetworkTools(tools: Seq[String]): ClaudeAgent =
      agent.withNetworkToolSet(ClaudeNetworkTools.validated(tools))
