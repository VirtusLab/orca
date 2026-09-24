package orca.tools.pi

import orca.agents.{Agent, AgentConfig, PiAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** The default pi agent. Pi supports many providers and fuzzy model patterns
  * through its own CLI, so it has no named tiers: pin one with
  * `pi.withModel(Model("..."))`.
  */
object PiAgents:

  /** The default pi agent for a run: standard config. */
  def default(wiring: AgentWiring): PiAgent =
    Agent(
      backend = PiBackend.create(OsProcCliRunner, wiring.workDir),
      config = AgentConfig(),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "pi"
    )
