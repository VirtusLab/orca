package orca.runner

import orca.agents.{BackendTag, DefaultPrompts}
import orca.backend.{AgentResult, AgentWiring, Conversation, Interaction}
import orca.events.{OrcaListener, Pricing}
import orca.testkit.Usages.usage
import orca.tools.codex.CodexAgents

/** codex names no model on its `exec --json` stream, so the model the default
  * agent pins is the only thing a codex turn can be priced by. This is the
  * cross-module check neither side can make alone: the pin lives in the codex
  * module, the price table in flow.
  */
class DefaultCodexModelPricedTest extends munit.FunSuite:

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

  test("the default codex agent pins a model the default price table knows"):
    val codex = CodexAgents.default(
      AgentWiring(
        events = OrcaListener.noop,
        interaction = stubInteraction,
        workDir = os.pwd,
        prompts = DefaultPrompts
      )
    )
    assert(
      Pricing
        .resolve(
          Pricing.default.table,
          codex.configuredModel,
          usage(input = 1_000L, output = 100L)
        )
        .isDefined,
      s"codex's default model ${codex.configuredModel} is unpriced"
    )
