package orca.runner

import orca.agents.{Agent, BackendTag, DefaultPrompts}
import orca.backend.{AgentResult, AgentWiring, Conversation, Interaction}
import orca.events.{OrcaListener, Pricing}
import orca.testkit.Usages.usage
import orca.tools.claude.ClaudeAgents
import orca.tools.codex.CodexAgents
import orca.tools.gemini.GeminiAgents

/** The pins live in the backend modules, the price table in flow — neither side
  * can check on its own that a wired default turn is priceable. Gemini matters
  * most: it reports no cost on the wire, so a renamed slug silently produces an
  * unpriced `(unknown)` bucket.
  */
class DefaultModelsPricedTest extends munit.FunSuite:

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  private val wiring = AgentWiring(
    events = OrcaListener.noop,
    interaction = stubInteraction,
    workDir = os.pwd,
    prompts = DefaultPrompts
  )

  private def assertDefaultModelPriced(
      backend: String,
      agent: Agent[?]
  ): Unit =
    assert(
      Pricing
        .resolve(
          Pricing.default.table,
          agent.configuredModel,
          usage(input = 1_000L, output = 100L)
        )
        .isDefined,
      s"$backend's default model ${agent.configuredModel} is unpriced"
    )

  test("the default claude agent pins a model the default price table knows"):
    assertDefaultModelPriced("claude", ClaudeAgents.default(wiring))

  test("the default codex agent pins a model the default price table knows"):
    assertDefaultModelPriced("codex", CodexAgents.default(wiring))

  test("the default gemini agent pins a model the default price table knows"):
    assertDefaultModelPriced("gemini", GeminiAgents.default(wiring))
