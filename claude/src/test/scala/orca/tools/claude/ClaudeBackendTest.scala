package orca.tools.claude

import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.{OrcaFlowException}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import ox.channels.BufferCapacity
import ox.supervised

class ClaudeBackendTest extends munit.FunSuite:

  /** Stream-json transcript for a clean autonomous call. Order matters:
    * `system.init` first, then the `result` message; `closeStdout` triggers EOF
    * so the reader settles.
    */
  private def successfulProcess(
      sessionId: String = "sess-123",
      output: String = "hello world",
      inputTokens: Long = 10L,
      outputTokens: Long = 5L,
      cost: Option[BigDecimal] = Some(BigDecimal("0.0012")),
      model: String = "claude-sonnet-4-6"
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      s"""{"type":"system","subtype":"init","session_id":"$sessionId","model":"$model"}"""
    )
    val costFrag = cost.fold("")(c => s""","total_cost_usd":$c""")
    p.enqueueStdout(
      s"""{"type":"result","subtype":"success","session_id":"$sessionId","result":"$output","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}$costFrag,"is_error":false,"model":"$model"}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    p

  /** Run a test body that needs a ClaudeBackend. `supervised:` provides the Ox
    * capability the constructor requires.
    */
  private def withBackend[T](runner: SpawnStubCliRunner)(
      body: ClaudeBackend => T
  ): T =
    supervised:
      given BufferCapacity = BufferCapacity(8)
      body(new ClaudeBackend(runner))

  test("runHeadless invokes claude in stream-json mode (no --mcp-config)"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ =
        backend.runHeadless("summarize", LlmConfig.default, os.temp.dir())
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--input-format", "stream-json")))
      assert(args.containsSlice(Seq("--output-format", "stream-json")))
      // No ask_user MCP on the autonomous path.
      assert(!args.contains("--mcp-config"), args)

  test("runHeadless parses session id, output, usage, and cost"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val result = backend.runHeadless("x", LlmConfig.default, os.temp.dir())
      assertEquals(SessionId.value(result.sessionId), "sess-123")
      assertEquals(result.output, "hello world")
      assertEquals(result.usage.inputTokens, 10L)
      assertEquals(result.usage.outputTokens, 5L)
      assertEquals(result.usage.cost, Some(BigDecimal("0.0012")))

  test("runHeadless throws when the result message reports is_error"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"system","subtype":"init","session_id":"s","model":"claude-haiku-4-5"}"""
    )
    p.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"s","result":"denied","usage":{"input_tokens":0,"output_tokens":0},"is_error":true}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test("runHeadless throws when the subprocess exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(2)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test(
    "runHeadless writes the system prompt to a file when config provides one"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val config = LlmConfig(systemPrompt = Some("you are a poet"))
      val _ = backend.runHeadless("x", config, workDir)
      val file = workDir / ".claude" / "orca-system-prompt.md"
      assert(os.exists(file))
      // Autonomous path doesn't append the ask_user hint.
      assertEquals(os.read(file), "you are a poet")

  test("continueHeadless passes --resume <id> and returns the new session id"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess(sessionId = "sess-456"))
    )
    withBackend(runner): backend =>
      val existing = SessionId[BackendTag.ClaudeCode.type]("sess-123")
      val result = backend.continueHeadless(
        existing,
        "keep going",
        LlmConfig.default,
        os.temp.dir()
      )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--resume", "sess-123")))
      assertEquals(SessionId.value(result.sessionId), "sess-456")
