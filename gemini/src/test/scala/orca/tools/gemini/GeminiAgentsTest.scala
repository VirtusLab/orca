package orca.tools.gemini

import orca.backend.SupervisedBackend
import orca.agents.{AgentConfig, GeminiAgent}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import orca.testkit.TestAgent
import orca.tools.gemini.GeminiAgents.flash

class GeminiAgentsTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

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
  )(body: GeminiAgent => Unit): Unit =
    SupervisedBackend.using(new GeminiBackend(runner)): backend =>
      body(TestAgent(backend, config = config))

  test("the base tool's pinned model reaches the CLI --model flag"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      AgentConfig().copy(model = Some(GeminiModels.Pro))
    ): tool =>
      val _ = tool.run("q")
      assert(
        runner.calls.head
          .containsSlice(Seq("--model", "gemini-3.1-pro-preview")),
        s"expected the pro pin; got: ${runner.calls.head}"
      )

  test("flash opts the model down to gemini-3.8-flash"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    toolWith(
      runner,
      AgentConfig().copy(model = Some(GeminiModels.Pro))
    ): tool =>
      val _ = tool.flash.run("q")
      assert(
        runner.calls.head.containsSlice(Seq("--model", "gemini-3.8-flash")),
        s"expected the flash pin; got: ${runner.calls.head}"
      )
