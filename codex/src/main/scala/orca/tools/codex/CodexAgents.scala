package orca.tools.codex

import orca.agents.{AgentConfig, CodexAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default codex agent. The concrete
  * [[DefaultCodexAgent]] / [[CodexBackend]] stay `private[orca]`; this object
  * is the user-facing way to build a standard codex wired into a run.
  */
object CodexAgents:

  /** The default codex agent for a run: standard config (no pinned model). */
  def default(wiring: AgentWiring): CodexAgent =
    new DefaultCodexAgent(
      backend = new CodexBackend(OsProcCliRunner),
      config = AgentConfig(),
      prompts = wiring.prompts,
      workDir = wiring.workDir,
      events = wiring.events,
      interaction = wiring.interaction
    )
