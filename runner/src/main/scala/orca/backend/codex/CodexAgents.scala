package orca.backend.codex

import orca.agents.{Agent, AgentConfig, CodexAgent}
import orca.backend.AgentWiring
import orca.effect.{FileSystem, Network, Processes}
import orca.subprocess.OsProcCliRunner

/** The default codex agent and the codex model tiers. Bare `codex` pins the
  * strong model; `codex.mini` opts down to the cheap tier, and
  * `codex.withModel(Model("..."))` pins any other id the CLI offers.
  */
object CodexAgents:

  /** The default codex agent for a run: GPT-6 Sol pinned (the strong model);
    * `.mini` opts down for cheap one-shots.
    */
  def default(wiring: AgentWiring): CodexAgent =
    val fs = FileSystem.ambient
    Agent(
      backend = new CodexBackend(
        Network.ambient,
        fs,
        OsProcCliRunner(Processes.ambient),
        CodexBackend.rolloutProbe(fs, os.home / ".codex" / "sessions"),
        workDir = wiring.workDir
      ),
      config = AgentConfig(model = Some(CodexModels.Sol)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  extension (agent: CodexAgent)
    def mini: CodexAgent = agent.withModel(CodexModels.Luna)
