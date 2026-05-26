package orca.tools.codex

import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import orca.{OrcaFlowException}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class CodexBackendTest extends munit.FunSuite:

  private def clientSid: SessionId[BackendTag.Codex.type] =
    SessionId[BackendTag.Codex.type]("00000000-0000-0000-0000-000000000000")

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
    val result =
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
    // The returned session id is the client-allocated one — the server's
    // thr-42 is mapped internally so subsequent calls can resume it without
    // the caller having to thread a new id back in.
    assertEquals(result.sessionId, clientSid)
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
    val result =
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
    assertEquals(result.model, Some(Model("gpt-5")))

  test("runAutonomous throws when codex exits without turn.completed"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"thread.started","thread_id":"thr-x"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    intercept[OrcaFlowException]:
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())

  test("runAutonomous throws with the exit code when codex exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
    assert(
      ex.getMessage.contains("exited with code 7"),
      s"expected the exit code in the failure message; got: ${ex.getMessage}"
    )

  test("non-zero exit attaches buffered stderr to the exception message"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Error: thread/resume failed: not found")
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
    assert(
      ex.getMessage.contains("thread/resume failed: not found"),
      s"expected stderr in the exception; got: ${ex.getMessage}"
    )

  test("`Reading additional input from stdin` stderr noise is filtered"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Reading additional input from stdin")
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
    assert(
      !ex.getMessage.contains("Reading additional input from stdin"),
      s"filtered noise leaked into the exception: ${ex.getMessage}"
    )

  test("systemPrompt is folded into the user prompt as a preamble"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val _ = backend.runAutonomous(
      "list files",
      clientSid,
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      os.temp.dir()
    )
    val args = runner.calls.head
    val finalPrompt = args.last
    assert(finalPrompt.contains("be terse"))
    assert(finalPrompt.contains("list files"))

  test(
    "second runAutonomous call with the same client id resumes the mapped server thread"
  ):
    val runner = new SpawnStubCliRunner(
      List(
        successfulProcess("thr-server-1"),
        successfulProcess("thr-server-1")
      )
    )
    val backend = new CodexBackend(runner)
    val workDir = os.temp.dir()
    val _ = backend.runAutonomous("first", clientSid, LlmConfig.default, workDir)
    val _ = backend.runAutonomous("again", clientSid, LlmConfig.default, workDir)
    val firstArgs = runner.calls(0)
    val secondArgs = runner.calls(1)
    assert(!firstArgs.contains("resume"), firstArgs)
    // Second call routes through `codex exec resume … <server-id>` — the
    // server id was learned from the first call's thread.started.
    assert(secondArgs.contains("resume"), secondArgs)
    assert(secondArgs.contains("thr-server-1"), secondArgs)

  test("runInteractive writes the output schema to a file in the workdir"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val workDir = os.temp.dir()
    val _ = backend.runInteractive(
      "q",
      clientSid,
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
