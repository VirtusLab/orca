package orca.tools.codex

import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import orca.{OrcaFlowException}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class CodexBackendTest extends munit.FunSuite:

  private def successfulProcess(
      threadId: String = "thr-test",
      message: String = "hello world",
      inputTokens: Long = 10L,
      outputTokens: Long = 5L
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(s"""{"type":"thread.started","thread_id":"$threadId"}""")
    p.enqueueStdout(s"""{"type":"turn.started"}""")
    p.enqueueStdout(
      s"""{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"$message"}}"""
    )
    p.enqueueStdout(
      s"""{"type":"turn.completed","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt() // mark exited so waitForExit returns
    p

  test("runAutonomous parses thread id, last agent_message, and usage"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-42", "the answer", 100L, 25L))
    )
    val backend = new CodexBackend(runner)
    val result = backend.runAutonomous("q", LlmConfig.default, os.temp.dir())
    assertEquals(SessionId.value(result.sessionId), "thr-42")
    assertEquals(result.output, "the answer")
    assertEquals(result.usage.inputTokens, 100L)
    assertEquals(result.usage.outputTokens, 25L)

  test("runAutonomous surfaces the model id reported on thread.started"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-m","model":"gpt-5"}"""
    )
    p.enqueueStdout("""{"type":"turn.started"}""")
    p.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"hi"}}"""
    )
    p.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val result = backend.runAutonomous("q", LlmConfig.default, os.temp.dir())
    assertEquals(result.model, Some(Model("gpt-5")))

  test("runAutonomous throws when codex exits without turn.completed"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"thread.started","thread_id":"thr-x"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    intercept[OrcaFlowException]:
      backend.runAutonomous("q", LlmConfig.default, os.temp.dir())

  test("runAutonomous throws with the exit code when codex exits non-zero"):
    // FakePipedCliProcess hardcodes tryExitCode = 0 when alive=false;
    // override to drive a non-zero exit so the failure branch can fire.
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", LlmConfig.default, os.temp.dir())
    assert(
      ex.getMessage.contains("exited with code 7"),
      s"expected the exit code in the failure message; got: ${ex.getMessage}"
    )

  test("non-zero exit attaches buffered stderr to the exception message"):
    // Listener-less callers (default OrcaListener.noop) would otherwise
    // lose stderr entirely on a non-zero exit; the diagnosticContext hook
    // on CodexConversation folds buffered lines into the message.
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Error: thread/resume failed: not found")
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", LlmConfig.default, os.temp.dir())
    assert(
      ex.getMessage.contains("thread/resume failed: not found"),
      s"expected stderr in the exception; got: ${ex.getMessage}"
    )

  test("`Reading additional input from stdin` stderr noise is filtered"):
    // codex emits the prompt about reading stdin on every exec when
    // stdin is piped; CodexConversation filters it before buffering, so
    // it must NOT appear in the failure message even when other stderr
    // lines do.
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Reading additional input from stdin")
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", LlmConfig.default, os.temp.dir())
    assert(
      !ex.getMessage.contains("Reading additional input from stdin"),
      s"filtered noise leaked into the exception: ${ex.getMessage}"
    )

  test("systemPrompt is folded into the user prompt as a preamble"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val _ = backend.runAutonomous(
      "list files",
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      os.temp.dir()
    )
    val args = runner.calls.head
    val finalPrompt = args.last
    assert(finalPrompt.contains("be terse"))
    assert(finalPrompt.contains("list files"))

  test("continueAutonomous returns the new session id from the resumed run"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-resumed"))
    )
    val backend = new CodexBackend(runner)
    val sid = SessionId[BackendTag.Codex.type]("thr-original")
    val result = backend.continueAutonomous(
      sid,
      "next step",
      LlmConfig.default,
      os.temp.dir()
    )
    assertEquals(SessionId.value(result.sessionId), "thr-resumed")

  test("runInteractive writes the output schema to a file in the workdir"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val workDir = os.temp.dir()
    val _ = backend.runInteractive(
      "q",
      displayPrompt = "q",
      LlmConfig.default,
      workDir,
      Some("""{"type":"object"}""")
    )
    val schemaFile = workDir / ".codex" / "orca-output-schema.json"
    assert(os.exists(schemaFile))
    assertEquals(os.read(schemaFile), """{"type":"object"}""")
    val args = runner.calls.head
    assert(args.containsSlice(Seq("--output-schema", schemaFile.toString)))

  test(
    "continueInteractive does NOT pass --output-schema (codex resume rejects it)"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val sid = SessionId[BackendTag.Codex.type]("thr-old")
    val _ = backend.continueInteractive(
      sid,
      prompt = "next",
      displayPrompt = "next",
      LlmConfig.default,
      os.temp.dir(),
      Some("""{"type":"object"}""")
    )
    val args = runner.calls.head
    assert(!args.contains("--output-schema"))
