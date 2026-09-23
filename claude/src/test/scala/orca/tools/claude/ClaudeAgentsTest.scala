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

  test("withNetworkTools rejects a command-scoped entry"):
    // --tools drops a name it doesn't recognise silently, so `Bash(gh api:*)`
    // would grant nothing and say nothing.
    val thrown = intercept[IllegalArgumentException]:
      claude.withNetworkTools(Seq("WebFetch", "Bash(gh api:*)"))
    assert(thrown.getMessage.contains("Bash(gh api:*)"), thrown.getMessage)

  test("withNetworkTools rejects a write-capable builtin"):
    // A bare "Bash" passes the shape check.
    val thrown = intercept[IllegalArgumentException]:
      claude.withNetworkTools(Seq("WebFetch", "Bash"))
    assert(thrown.getMessage.contains("Bash"), thrown.getMessage)
    assert(thrown.getMessage.contains("ToolSet.Full"), thrown.getMessage)
