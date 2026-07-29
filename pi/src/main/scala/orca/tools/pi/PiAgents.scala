package orca.tools.pi

import orca.agents.{AgentConfig, PiAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default pi agent. The concrete
  * [[DefaultPiAgent]] / [[PiBackend]] stay `private[orca]`; this object is the
  * user-facing way to build a standard pi wired into a run.
  */
object PiAgents:

  /** The default pi agent for a run: standard config. */
  def default(wiring: AgentWiring): PiAgent =
    new DefaultPiAgent(
      backend = PiBackend.create(OsProcCliRunner, wiring.workDir),
      config = AgentConfig(),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction
    )
