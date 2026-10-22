package orca.backend.gemini

import orca.agents.{Agent, AgentConfig, GeminiAgent}
import orca.backend.AgentWiring
import orca.effect.{FileSystem, Network, Processes}
import orca.subprocess.OsProcCliRunner

/** The default gemini agent and the Gemini model tiers. */
object GeminiAgents:

  /** The default gemini agent for a run: Gemini Pro pinned (the strong model);
    * `.flash` opts down for cheap one-shots.
    */
  def default(wiring: AgentWiring): GeminiAgent =
    Agent(
      backend = new GeminiBackend(
        Network.ambient,
        FileSystem.ambient,
        OsProcCliRunner(Processes.ambient),
        workDir = wiring.workDir
      ),
      config = AgentConfig(model = Some(GeminiModels.Pro)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  extension (agent: GeminiAgent)
    def flash: GeminiAgent = agent.withModel(GeminiModels.Flash)
