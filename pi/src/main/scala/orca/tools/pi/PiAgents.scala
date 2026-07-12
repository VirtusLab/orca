package orca.tools.pi

import orca.agents.{AgentConfig, PiAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default pi agent. The concrete
  * [[DefaultPiAgent]] / [[PiBackend]] stay `private[orca]`; this object is the
  * user-facing way to build a standard pi wired into a run.
  */
object PiAgents:

  /** The default pi agent for a run: standard config. Pi model selection is
    * left to generic [[AgentConfig.model]] values since Pi supports many
    * providers and fuzzy model patterns through its own CLI.
    */
  def default(wiring: AgentWiring): PiAgent =
    new DefaultPiAgent(
      backend = new PiBackend(OsProcCliRunner, workDir = wiring.workDir),
      config = AgentConfig(),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction
    )
