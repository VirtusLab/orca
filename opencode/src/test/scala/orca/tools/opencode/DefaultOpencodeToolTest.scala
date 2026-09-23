package orca.tools.opencode

import orca.testkit.ScriptedBackend
import orca.backend.{
  Interaction,
  AgentResult,
  TurnRequest,
  ObservedConversation
}
import orca.events.OrcaListener
import orca.agents.{
  BackendTag,
  DefaultPrompts,
  AgentConfig,
  OpencodeAgent,
  ToolSet
}

class DefaultOpencodeAgentTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  /** Captures the config the tool resolves for an autonomous call. */
  private class RecordingBackend extends ScriptedBackend(BackendTag.Opencode):
    var lastConfig: Option[AgentConfig] = None
    protected def reply(
        turn: TurnRequest[BackendTag.Opencode.type]
    ): AgentResult[BackendTag.Opencode.type] =
      lastConfig = Some(turn.config)
      ScriptedBackend.result("ok")

  private val noInteraction: Interaction = new Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: ObservedConversation[B]
    ): AgentResult[B] = throw new UnsupportedOperationException

  private def toolWith(backend: RecordingBackend): OpencodeAgent =
    new DefaultOpencodeAgent(
      backend,
      AgentConfig(),
      DefaultPrompts,
      OrcaListener.noop,
      noInteraction
    )

  /** Run an autonomous call and return the model id the backend saw. */
  private def modelOf(
      tool: OpencodeAgent,
      backend: RecordingBackend
  ): Option[String] =
    val _ = tool.run("x")
    backend.lastConfig.flatMap(_.model).map(_.name)

  test("provider-prefixed accessors pin the right provider/model id"):
    val b = new RecordingBackend
    assertEquals(
      modelOf(toolWith(b).anthropicOpus, b),
      Some("anthropic/claude-opus-5-5")
    )
    assertEquals(
      modelOf(toolWith(b).anthropicSonnet, b),
      Some("anthropic/claude-sonnet-5")
    )
    assertEquals(
      modelOf(toolWith(b).anthropicHaiku, b),
      Some("anthropic/claude-haiku-4-5")
    )
    assertEquals(
      modelOf(toolWith(b).openaiAstra, b),
      Some("openai/gpt-6-astra")
    )
    assertEquals(modelOf(toolWith(b).openaiSol, b), Some("openai/gpt-6-sol"))
    assertEquals(
      modelOf(toolWith(b).openaiLuna, b),
      Some("openai/gpt-6-luna")
    )

  test("withModel pins an arbitrary provider/model id (self-hosted)"):
    val b = new RecordingBackend
    assertEquals(
      modelOf(toolWith(b).withModel("ollama/llama3.1"), b),
      Some("ollama/llama3.1")
    )

  test("withReadOnly pins tools to ReadOnly, keeping the model pin"):
    val b = new RecordingBackend
    val _ = toolWith(b).anthropicOpus.withReadOnly.run("x")
    assertEquals(b.lastConfig.map(_.tools), Some(ToolSet.ReadOnly))
    assertEquals(
      b.lastConfig.flatMap(_.model).map(_.name),
      Some("anthropic/claude-opus-5-5")
    )

  test("withName renames without touching config"):
    val b = new RecordingBackend
    assertEquals(toolWith(b).withName("planner").name, "planner")
