package orca.backend.cursor

import orca.agents.{Agent, AgentConfig, CursorAgent, Model}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

object CursorAgents:

  def default(wiring: AgentWiring): CursorAgent =
    Agent(
      backend = CursorBackend.create(OsProcCliRunner, wiring.workDir),
      config = AgentConfig(model = Some(Model("composer-2.5"))),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  extension (agent: CursorAgent)
    def composer: CursorAgent = agent.withModel(Model("composer-2.5"))
