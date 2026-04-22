package orca.claude

import orca.{Backend, LlmConfig, LlmResult, OrcaFlowException, SessionId}
import orca.subprocess.{CliResult, StubCliRunner}

class ClaudeBackendTest extends munit.FunSuite:

  private val sampleJson =
    """{
      |  "session_id": "sess-123",
      |  "result": "hello world",
      |  "usage": {"input_tokens": 10, "output_tokens": 5},
      |  "total_cost_usd": 0.0012,
      |  "is_error": false
      |}""".stripMargin

  private def stubBackend(canned: CliResult): (StubCliRunner, ClaudeBackend) =
    val cli = new StubCliRunner(canned)
    (cli, new ClaudeBackend(cli))

  test("runHeadless invokes claude -p with JSON output format"):
    val (cli, backend) = stubBackend(CliResult(0, sampleJson, ""))
    val _ = backend.runHeadless("summarize", LlmConfig.default, os.temp.dir())
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("claude", "-p", "summarize")))
    assert(args.containsSlice(Seq("--output-format", "json")))

  test("runHeadless parses session id, output, and usage into LlmResult"):
    val (_, backend) = stubBackend(CliResult(0, sampleJson, ""))
    val result = backend.runHeadless("x", LlmConfig.default, os.temp.dir())
    assertEquals(SessionId.value(result.sessionId), "sess-123")
    assertEquals(result.output, "hello world")
    assertEquals(result.usage.inputTokens, 10L)
    assertEquals(result.usage.outputTokens, 5L)
    assertEquals(result.usage.cost, Some(BigDecimal("0.0012")))

  test("runHeadless throws when the CLI exits non-zero"):
    val (_, backend) = stubBackend(CliResult(1, "", "boom"))
    intercept[OrcaFlowException]:
      backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test("runHeadless throws when the response reports is_error = true"):
    val errorJson =
      """{"session_id":"s","result":"denied","usage":{"input_tokens":0,"output_tokens":0},"is_error":true}"""
    val (_, backend) = stubBackend(CliResult(0, errorJson, ""))
    intercept[OrcaFlowException]:
      backend.runHeadless("x", LlmConfig.default, os.temp.dir())

  test(
    "runHeadless writes the system prompt to a file when config provides one"
  ):
    val (_, backend) = stubBackend(CliResult(0, sampleJson, ""))
    val workDir = os.temp.dir()
    val config = LlmConfig(systemPrompt = Some("you are a poet"))
    val _ = backend.runHeadless("x", config, workDir)
    val file = workDir / ".claude" / "orca-system-prompt.md"
    assert(os.exists(file))
    assertEquals(os.read(file), "you are a poet")

  test("continueHeadless passes --resume <id> and returns the new session id"):
    val resumedJson =
      """{"session_id":"sess-456","result":"resumed","usage":{"input_tokens":1,"output_tokens":2}}"""
    val (cli, backend) = stubBackend(CliResult(0, resumedJson, ""))
    val existing = SessionId[Backend.ClaudeCode.type]("sess-123")
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
