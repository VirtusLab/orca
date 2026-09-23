package orca.tools.claude

import orca.agents.{DefaultPrompts, NetworkTools}
import orca.backend.AgentWiring
import orca.events.OrcaListener
import orca.testkit.TestAgent
import orca.tools.claude.ClaudeAgents.withNetworkTools

class ClaudeAgentsTest extends munit.FunSuite:

  private val claude = ClaudeAgents.default(
    AgentWiring(
      OrcaListener.noop,
      TestAgent.UnusedInteraction,
      os.pwd,
      DefaultPrompts
    )
  )

  // One backend holds the sessions, the close latch and the enforcement
  // notices, so a sibling on another backend would lose all three.
  test("withNetworkTools stays on the agent's backend"):
    assert(claude.withNetworkTools(Seq("WebFetch")).sharesBackendWith(claude))

  test("withNetworkTools pins the tools on the agent's config"):
    assertEquals(
      claude.withNetworkTools(Seq("WebFetch")).config.networkTools,
      Some(NetworkTools(Seq("WebFetch")))
    )
