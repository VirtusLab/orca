package orca.backend

import orca.agents.Prompts
import orca.events.OrcaListener

/** Everything the runtime wires into an agent at construction: the run's event
  * sink (costs/steps land in the tracker and terminal), the interaction, the
  * working directory, and the prompt templates. Override factories receive this
  * so user-supplied agents are first-class citizens of the run — wired into the
  * same dispatcher as the defaults, not event-blind.
  */
final case class AgentWiring(
    events: OrcaListener,
    interaction: Interaction,
    workDir: os.Path,
    prompts: Prompts
)
