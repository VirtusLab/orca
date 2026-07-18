package orca.tools.gemini

import orca.backend.{Conversation, Interaction, AgentResult, SupervisedBackend}
import orca.events.OrcaListener
import orca.agents.{BackendTag, DefaultPrompts, AgentConfig}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class DefaultGeminiAgentTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: Conversation[B]
    )(using ox.Ox): AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

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
      config: AgentConfig
  )(body: DefaultGeminiAgent => Unit): Unit =
    SupervisedBackend.using(new GeminiBackend(runner)): backend =>
      body(
        new DefaultGeminiAgent(
          backend = backend,
          config = config,
          prompts = DefaultPrompts,
          events = OrcaListener.noop,
          interaction = stubInteraction
        )
      )

  test("the base tool's pinned model reaches the CLI --model flag"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      AgentConfig().copy(model = Some(DefaultGeminiAgent.Pro))
    ): tool =>
      val _ = tool.run("q")
      assert(
        runner.calls.head.containsSlice(Seq("--model", "gemini-2.5-pro")),
        s"expected the pro pin; got: ${runner.calls.head}"
      )

  test("flash opts the model down to gemini-2.5-flash"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      AgentConfig().copy(model = Some(DefaultGeminiAgent.Pro))
    ): tool =>
      val _ = tool.flash.run("q")
      assert(
        runner.calls.head.containsSlice(Seq("--model", "gemini-2.5-flash")),
        s"expected the flash pin; got: ${runner.calls.head}"
      )

  test("withName preserves the GeminiAgent type and renames"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(runner, AgentConfig()): tool =>
      val renamed = tool.withName("planner")
      assertEquals(renamed.name, "planner")
