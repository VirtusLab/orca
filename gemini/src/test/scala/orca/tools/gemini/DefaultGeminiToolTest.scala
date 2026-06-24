package orca.tools.gemini

import orca.backend.{Conversation, Interaction, LlmResult, SupervisedBackend}
import orca.events.OrcaListener
import orca.llm.{BackendTag, DefaultPrompts, LlmConfig}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class DefaultGeminiToolTest extends munit.FunSuite:

  // LLM `run` is now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    ): LlmResult[B] = throw new UnsupportedOperationException("test stub")

  private def successfulProcess(): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"init","session_id":"s"}""")
    p.enqueueStdout("""{"type":"message","role":"assistant","content":"ok"}""")
    p.enqueueStdout(
      """{"type":"result","status":"success","stats":{"input_tokens":1,"output_tokens":1}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    p

  private def toolWith(
      runner: SpawnStubCliRunner,
      config: LlmConfig
  )(body: DefaultGeminiTool => Unit): Unit =
    SupervisedBackend.using(new GeminiBackend(runner)): backend =>
      body(
        new DefaultGeminiTool(
          backend = backend,
          config = config,
          prompts = DefaultPrompts,
          workDir = os.temp.dir(),
          events = OrcaListener.noop,
          interaction = stubInteraction
        )
      )

  test("the base tool's pinned model reaches the CLI --model flag"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      LlmConfig.default.copy(model = Some(DefaultGeminiTool.Pro))
    ): tool =>
      val _ = tool.autonomous.run("q")
      assert(
        runner.calls.head.containsSlice(Seq("--model", "gemini-2.5-pro")),
        s"expected the pro pin; got: ${runner.calls.head}"
      )

  test("flash opts the model down to gemini-2.5-flash"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      LlmConfig.default.copy(model = Some(DefaultGeminiTool.Pro))
    ): tool =>
      val _ = tool.flash.autonomous.run("q")
      assert(
        runner.calls.head.containsSlice(Seq("--model", "gemini-2.5-flash")),
        s"expected the flash pin; got: ${runner.calls.head}"
      )

  test("withName preserves the GeminiTool type and renames"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(runner, LlmConfig.default): tool =>
      val renamed = tool.withName("planner")
      assertEquals(renamed.name, "planner")
