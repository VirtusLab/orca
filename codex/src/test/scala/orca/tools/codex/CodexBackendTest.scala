package orca.tools.codex

import orca.{BackendTag, LlmConfig, OrcaFlowException, SessionId}
import orca.subprocess.{
  CliResult,
  CliRunner,
  FakePipedCliProcess,
  PipedCliProcess
}

import java.util.concurrent.atomic.AtomicReference

/** Test runner that hands out a pre-scripted [[FakePipedCliProcess]] each time
  * the subject calls `spawnPiped`. Records the args so the test can assert flag
  * mapping. Single-call: each prepared process is consumed by exactly one
  * spawn.
  */
private class SpawnStubCliRunner(prepared: List[FakePipedCliProcess])
    extends CliRunner:
  private val queue: AtomicReference[List[FakePipedCliProcess]] =
    AtomicReference(prepared)
  private val recorded: AtomicReference[List[List[String]]] =
    AtomicReference(Nil)

  def calls: List[List[String]] = recorded.get().reverse

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    throw new UnsupportedOperationException("CodexBackend does not call run")

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    val _ = recorded.updateAndGet(args.toList :: _)
    val next = queue
      .getAndUpdate(_.drop(1))
      .headOption
      .getOrElse(
        throw new IllegalStateException("ran out of prepared processes")
      )
    next

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

  test("runHeadless parses thread id, last agent_message, and usage"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-42", "the answer", 100L, 25L))
    )
    val backend = new CodexBackend(runner)
    val result = backend.runHeadless("q", LlmConfig.default, os.temp.dir())
    assertEquals(SessionId.value(result.sessionId), "thr-42")
    assertEquals(result.output, "the answer")
    assertEquals(result.usage.inputTokens, 100L)
    assertEquals(result.usage.outputTokens, 25L)

  test("runHeadless surfaces the model id reported on thread.started"):
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
    val result = backend.runHeadless("q", LlmConfig.default, os.temp.dir())
    assertEquals(result.model, Some("gpt-5"))

  test("runHeadless throws when codex exits without turn.completed"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"thread.started","thread_id":"thr-x"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    intercept[OrcaFlowException]:
      backend.runHeadless("q", LlmConfig.default, os.temp.dir())

  test(
    "runHeadless surfaces stderr in the failure message when codex exits non-zero"
  ):
    // FakePipedCliProcess.waitForExit hardcodes 0; subclass to drive a
    // non-zero exit so the stderr-diagnostic branch can fire.
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def waitForExit(): Int = 7
    p.enqueueStdout("""{"type":"thread.started","thread_id":"thr-fail"}""")
    p.enqueueStderr("Error: thread/resume failed: not found")
    p.closeStdout()
    p.closeStderr()
    val backend = new CodexBackend(new SpawnStubCliRunner(List(p)))
    val ex = intercept[OrcaFlowException]:
      backend.runHeadless("q", LlmConfig.default, os.temp.dir())
    assert(
      ex.getMessage.contains("thread/resume failed"),
      s"expected stderr to be in the failure message; got: ${ex.getMessage}"
    )
    assert(
      ex.getMessage.contains("exit 7"),
      s"expected the exit code to be in the failure message; got: ${ex.getMessage}"
    )

  test("systemPrompt is folded into the user prompt as a preamble"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = new CodexBackend(runner)
    val _ = backend.runHeadless(
      "list files",
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      os.temp.dir()
    )
    val args = runner.calls.head
    val finalPrompt = args.last
    assert(finalPrompt.contains("be terse"))
    assert(finalPrompt.contains("list files"))

  test("continueHeadless returns the new session id from the resumed run"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-resumed"))
    )
    val backend = new CodexBackend(runner)
    val sid = SessionId[BackendTag.Codex.type]("thr-original")
    val result = backend.continueHeadless(
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
