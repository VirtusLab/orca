package orca.tools.pi

import orca.backend.{Interaction, AgentBackend}
import orca.events.OrcaListener
import orca.agents.{BackendTag, AgentConfig, PiAgent, Prompts}
import orca.agents.BaseAgent

/** Default [[PiAgent]] implementation. Inherits the autonomous-text and
  * structured-output plumbing from [[BaseAgent]]; Pi model selection is left to
  * generic [[AgentConfig.model]] values because Pi supports many providers and
  * fuzzy model patterns through its own CLI.
  */
private[orca] class DefaultPiAgent(
    backend: AgentBackend[BackendTag.Pi.type],
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "pi"
) extends BaseAgent[BackendTag.Pi.type, PiAgent](
      backend,
      config,
      prompts,
      events,
      interaction
    )
    with PiAgent:

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name
  ): PiAgent =
    new DefaultPiAgent(
      backend,
      config,
      prompts,
      events,
      interaction,
      name
    )
