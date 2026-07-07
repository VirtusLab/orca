package orca.tools.gemini

import orca.agents.{AgentConfig, GeminiAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default gemini agent. The concrete
  * [[DefaultGeminiAgent]] / [[GeminiBackend]] stay `private[orca]`; this object
  * is the user-facing way to build a standard gemini wired into a run.
  */
object GeminiAgents:

  /** The default gemini agent for a run: Gemini Pro pinned (the strong model,
    * like claude defaults to Opus for the long-lived implementer); `.flash`
    * opts down for cheap one-shots.
    */
  def default(wiring: AgentWiring): GeminiAgent =
    new DefaultGeminiAgent(
      backend = new GeminiBackend(OsProcCliRunner, workDir = wiring.workDir),
      config = AgentConfig(model = Some(DefaultGeminiAgent.Pro)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction
    )
