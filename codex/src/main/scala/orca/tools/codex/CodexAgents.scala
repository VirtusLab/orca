package orca.tools.codex

import orca.agents.{AgentConfig, CodexAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default codex agent. The concrete
  * [[DefaultCodexAgent]] / [[CodexBackend]] stay `private[orca]`; this object
  * is the user-facing way to build a standard codex wired into a run.
  */
object CodexAgents:

  /** The default codex agent for a run: GPT-5.6 Sol pinned (the strong model);
    * `.mini` opts down for cheap one-shots.
    */
  def default(wiring: AgentWiring): CodexAgent =
    new DefaultCodexAgent(
      backend = new CodexBackend(OsProcCliRunner, workDir = wiring.workDir),
      config = AgentConfig(model = Some(DefaultCodexAgent.Sol)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction
    )
