package orca.tools.opencode

import orca.backend.{Conversation, Interaction, LlmBackend, LlmResult}
import orca.events.{OrcaListener, Usage}
import orca.llm.{BackendTag, DefaultPrompts, LlmConfig, OpencodeTool, SessionId}

class DefaultOpencodeToolTest extends munit.FunSuite:

  /** Captures the config the tool resolves for an autonomous call. */
  private class RecordingBackend extends LlmBackend[BackendTag.Opencode.type]:
    var lastConfig: Option[LlmConfig] = None
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        config: LlmConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): LlmResult[BackendTag.Opencode.type] =
      lastConfig = Some(config)
      LlmResult(session, "ok", Usage(0L, 0L, None))
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Opencode.type],
        displayPrompt: String,
        config: LlmConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    ): Conversation[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException

  private val noInteraction: Interaction = new Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): LlmResult[B] = throw new UnsupportedOperationException

  private def toolWith(backend: RecordingBackend): OpencodeTool =
    new DefaultOpencodeTool(
      backend,
      LlmConfig.default,
      DefaultPrompts,
      os.temp.dir(),
      OrcaListener.noop,
      noInteraction
    )

  /** Run an autonomous call and return the model id the backend saw. */
  private def modelOf(
      tool: OpencodeTool,
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
      Some("anthropic/claude-sonnet-4-6")
    )
    assertEquals(
      modelOf(toolWith(b).anthropicHaiku, b),
      Some("anthropic/claude-haiku-4-5")
    )
    assertEquals(modelOf(toolWith(b).openaiGpt5, b), Some("openai/gpt-5.4"))
    assertEquals(
      modelOf(toolWith(b).openaiGpt5Codex, b),
      Some("openai/gpt-5.3-codex")
    )
    assertEquals(
      modelOf(toolWith(b).openaiGpt5Mini, b),
      Some("openai/gpt-5-mini")
    )

  test("withModel pins an arbitrary provider/model id (self-hosted)"):
    val b = new RecordingBackend
    assertEquals(
      modelOf(toolWith(b).withModel("ollama/llama3.1"), b),
      Some("ollama/llama3.1")
    )

  test("withReadOnly flips the read-only flag, keeping the model pin"):
    val b = new RecordingBackend
    val _ = toolWith(b).anthropicOpus.withReadOnly.autonomous.run("x")
    assertEquals(b.lastConfig.map(_.readOnly), Some(true))
    assertEquals(
      b.lastConfig.flatMap(_.model).map(_.name),
      Some("anthropic/claude-opus-4-8")
    )

  test("withName renames without touching config"):
    val b = new RecordingBackend
    assertEquals(toolWith(b).withName("planner").name, "planner")
