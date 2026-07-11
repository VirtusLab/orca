package orca.tools.opencode

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  SessionRegistry,
  SessionSupport
}
import orca.events.{OrcaListener, Usage}
import orca.agents.{
  AutoApprove,
  BackendTag,
  DefaultPrompts,
  AgentConfig,
  Enforcement,
  OpencodeAgent,
  SessionId,
  ToolSet,
  onWire
}

class DefaultOpencodeAgentTest extends munit.FunSuite:

  // LLM `run` is now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  /** Captures the config the tool resolves for an autonomous call. */
  private class RecordingBackend extends AgentBackend[BackendTag.Opencode.type]:
    val workDir: os.Path = os.pwd
    var lastConfig: Option[AgentConfig] = None
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        config: AgentConfig,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Opencode.type] =
      lastConfig = Some(config)
      AgentResult(session.onWire, "ok", Usage(0L, 0L, None))
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        displayPrompt: String,
        config: AgentConfig,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    val sessions: SessionSupport[BackendTag.Opencode.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Opencode.type = BackendTag.Opencode
    def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement =
      Enforcement.Ignored

  private val noInteraction: Interaction = new Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] = throw new UnsupportedOperationException

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
    val _ = tool.autonomous.run("x")
    backend.lastConfig.flatMap(_.model).map(_.name)

  test("provider-prefixed accessors pin the right provider/model id"):
    val b = new RecordingBackend
    assertEquals(
      modelOf(toolWith(b).anthropicOpus, b),
      Some("anthropic/claude-opus-4-8")
    )
    assertEquals(
      modelOf(toolWith(b).anthropicSonnet, b),
      Some("anthropic/claude-sonnet-5")
    )
    assertEquals(
      modelOf(toolWith(b).anthropicHaiku, b),
      Some("anthropic/claude-haiku-4-5")
    )
    assertEquals(modelOf(toolWith(b).openaiSol, b), Some("openai/gpt-5.6-sol"))
    assertEquals(
      modelOf(toolWith(b).openaiTerra, b),
      Some("openai/gpt-5.6-terra")
    )
    assertEquals(
      modelOf(toolWith(b).openaiLuna, b),
      Some("openai/gpt-5.6-luna")
    )

  test("withModel pins an arbitrary provider/model id (self-hosted)"):
    val b = new RecordingBackend
    assertEquals(
      modelOf(toolWith(b).withModel("ollama/llama3.1"), b),
      Some("ollama/llama3.1")
    )

  test("withReadOnly pins tools to ReadOnly, keeping the model pin"):
    val b = new RecordingBackend
    val _ = toolWith(b).anthropicOpus.withReadOnly.autonomous.run("x")
    assertEquals(b.lastConfig.map(_.tools), Some(ToolSet.ReadOnly))
    assertEquals(
      b.lastConfig.flatMap(_.model).map(_.name),
      Some("anthropic/claude-opus-4-8")
    )

  test("withName renames without touching config"):
    val b = new RecordingBackend
    assertEquals(toolWith(b).withName("planner").name, "planner")
