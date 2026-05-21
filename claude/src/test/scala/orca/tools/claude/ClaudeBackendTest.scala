package orca.tools.claude

import orca.llm.{BackendTag, LlmConfig, SessionId}
import orca.{OrcaFlowException}
import orca.backend.LlmResult
import orca.subprocess.{CliResult, StubCliRunner}
import ox.channels.BufferCapacity
import ox.supervised

class ClaudeBackendTest extends munit.FunSuite:

  private val sampleJson =
    """{
      |  "session_id": "sess-123",
      |  "result": "hello world",
      |  "usage": {"input_tokens": 10, "output_tokens": 5},
      |  "total_cost_usd": 0.0012,
      |  "is_error": false
      |}""".stripMargin

  /** Run a test body that needs a ClaudeBackend. Wraps in `supervised:` so
    * the backend's Ox + BufferCapacity capabilities are satisfied; all
    * tests in this file exercise the headless path and don't actually need
    * the MCP machinery, but the constructor pulls those caps regardless.
    */
  private def withStubBackend[T](canned: CliResult)(
      body: (StubCliRunner, ClaudeBackend) => T
  ): T =
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val cli = new StubCliRunner(canned)
      body(cli, new ClaudeBackend(cli))

  test("runHeadless invokes claude -p with JSON output format"):
    withStubBackend(CliResult(0, sampleJson, "")): (cli, backend) =>
      val _ =
        backend.runHeadless("summarize", LlmConfig.default, os.temp.dir())
      val args = cli.lastCall.getOrElse(fail("expected a call")).args
      assert(args.containsSlice(Seq("claude", "-p", "summarize")))
      assert(args.containsSlice(Seq("--output-format", "json")))

  test("runHeadless parses session id, output, and usage into LlmResult"):
    withStubBackend(CliResult(0, sampleJson, "")): (_, backend) =>
      val result = backend.runHeadless("x", LlmConfig.default, os.temp.dir())
      assertEquals(SessionId.value(result.sessionId), "sess-123")
      assertEquals(result.output, "hello world")
      assertEquals(result.usage.inputTokens, 10L)
      assertEquals(result.usage.outputTokens, 5L)
      assertEquals(result.usage.cost, Some(BigDecimal("0.0012")))

  test("runHeadless throws when the CLI exits non-zero"):
    withStubBackend(CliResult(1, "", "boom")): (_, backend) =>
      intercept[OrcaFlowException]:
        backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test("runHeadless throws when the response reports is_error = true"):
    val errorJson =
      """{"session_id":"s","result":"denied","usage":{"input_tokens":0,"output_tokens":0},"is_error":true}"""
    withStubBackend(CliResult(0, errorJson, "")): (_, backend) =>
      intercept[OrcaFlowException]:
        backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test(
    "runHeadless writes the system prompt to a file when config provides one"
  ):
    withStubBackend(CliResult(0, sampleJson, "")): (_, backend) =>
      val workDir = os.temp.dir()
      val config = LlmConfig(systemPrompt = Some("you are a poet"))
      val _ = backend.runHeadless("x", config, workDir)
      val file = workDir / ".claude" / "orca-system-prompt.md"
      assert(os.exists(file))
      assertEquals(os.read(file), "you are a poet")

  test("continueHeadless passes --resume <id> and returns the new session id"):
    val resumedJson =
      """{"session_id":"sess-456","result":"resumed","usage":{"input_tokens":1,"output_tokens":2}}"""
    withStubBackend(CliResult(0, resumedJson, "")): (cli, backend) =>
      val existing = SessionId[BackendTag.ClaudeCode.type]("sess-123")
      val result = backend.continueHeadless(
        existing,
        "keep going",
        LlmConfig.default,
        os.temp.dir()
      )
      val args = cli.lastCall.getOrElse(fail("expected a call")).args
      assert(args.containsSlice(Seq("-p", "keep going")))
      assert(args.containsSlice(Seq("--resume", "sess-123")))
      assertEquals(SessionId.value(result.sessionId), "sess-456")
