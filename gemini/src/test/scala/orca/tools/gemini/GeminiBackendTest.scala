package orca.tools.gemini

import orca.backend.SupervisedBackend
import orca.agents.{BackendTag, AgentConfig, Model, SessionId, WireSessionId}
import orca.OrcaFlowException
import orca.subprocess.{
  CliResult,
  FakePipedCliProcess,
  SpawnStubCliRunner,
  StubCliRunner
}

class GeminiBackendTest extends munit.FunSuite:

  private def clientSid: SessionId[BackendTag.Gemini.type] =
    SessionId[BackendTag.Gemini.type]("00000000-0000-0000-0000-000000000000")

  private def withBackend[T](runner: SpawnStubCliRunner)(
      body: ox.Ox ?=> GeminiBackend => T
  ): T = SupervisedBackend.using(new GeminiBackend(runner))(body)

  private def successfulProcess(
      sessionId: String = "sess-test",
      message: String = "hello world",
      inputTokens: Long = 10L,
      outputTokens: Long = 5L,
      model: Option[String] = None
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    val modelField = model.map(m => s""","model":"$m"""").getOrElse("")
    p.enqueueStdout(
      s"""{"type":"init","session_id":"$sessionId"$modelField}"""
    )
    p.enqueueStdout(
      s"""{"type":"message","role":"assistant","content":"$message"}"""
    )
    p.enqueueStdout(
      s"""{"type":"result","status":"success","stats":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt() // mark exited so waitForExit returns
    p

  /** A process that streams `init` but never closes stdout — the reader blocks,
    * so the conversation's finalize (which restores settings.json) doesn't fire
    * mid-test. Used by interactive tests that inspect the registered file.
    */
  private def pendingProcess(): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"init","session_id":"sess-pending"}""")
    p

  test("runAutonomous parses session id, assistant content, and usage"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("sess-42", "the answer", 100L, 25L))
    )
    withBackend(runner): backend =>
      val result =
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )
      // The result reports the WIRE id — the server-minted sess-42 — while the
      // client→server mapping is recorded in the registry so subsequent calls
      // resume it.
      assertEquals(
        result.wireId,
        WireSessionId[BackendTag.Gemini.type]("sess-42")
      )
      assertEquals(result.output, "the answer")
      assertEquals(result.usage.inputTokens, 100L)
      assertEquals(result.usage.outputTokens, 25L)

  test("runAutonomous surfaces the model id reported on init"):
    val runner =
      new SpawnStubCliRunner(
        List(successfulProcess(model = Some("gemini-2.5-pro")))
      )
    withBackend(runner): backend =>
      val result =
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )
      assertEquals(result.model, Some(Model("gemini-2.5-pro")))

  test("runAutonomous throws when gemini exits without a result event"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"init","session_id":"s"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )

  test("runAutonomous throws with the exit code when gemini exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )
      assert(
        ex.getMessage.contains("exited with code 7"),
        s"expected the exit code in the message; got: ${ex.getMessage}"
      )

  test("non-zero exit attaches buffered stderr to the exception message"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Error: resume failed: session not found")
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )
      assert(
        ex.getMessage.contains("resume failed: session not found"),
        s"expected stderr in the exception; got: ${ex.getMessage}"
      )

  test("systemPrompt is folded into the user prompt as a preamble"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "list files",
        clientSid,
        AgentConfig.default.copy(systemPrompt = Some("be terse")),
        os.temp.dir()
      )
      val finalPrompt = runner.calls.head.last
      assert(finalPrompt.contains("be terse"))
      assert(finalPrompt.contains("list files"))

  test("a second call with the same client id resumes the mapped session"):
    val runner = new SpawnStubCliRunner(
      List(
        successfulProcess("sess-server-1"),
        successfulProcess("sess-server-1")
      )
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ =
        backend.runAutonomous("first", clientSid, AgentConfig.default, workDir)
      val _ =
        backend.runAutonomous("again", clientSid, AgentConfig.default, workDir)
      val firstArgs = runner.calls(0)
      val secondArgs = runner.calls(1)
      assert(!firstArgs.contains("--resume"), firstArgs.toString)
      assert(secondArgs.contains("--resume"), secondArgs.toString)
      assert(secondArgs.contains("sess-server-1"), secondArgs.toString)

  test("registerSession lets a follow-up autonomous call resume"):
    val runner = new SpawnStubCliRunner(List(successfulProcess("sess-via-int")))
    withBackend(runner): backend =>
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Gemini.type]("sess-via-int")
      )
      val _ =
        backend.runAutonomous(
          "after",
          clientSid,
          AgentConfig.default,
          os.temp.dir()
        )
      val args = runner.calls.head
      assert(args.contains("--resume"), args.toString)
      assert(args.contains("sess-via-int"), args.toString)

  test("distinct client ids both start fresh — no cross-client mapping"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("sess-A"), successfulProcess("sess-B"))
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val sidA = SessionId[BackendTag.Gemini.type]("aaaaaaaa")
      val sidB = SessionId[BackendTag.Gemini.type]("bbbbbbbb")
      val _ = backend.runAutonomous("for A", sidA, AgentConfig.default, workDir)
      val _ = backend.runAutonomous("for B", sidB, AgentConfig.default, workDir)
      assert(
        !runner.calls(1).contains("--resume"),
        s"second call with a new client id must NOT resume; got: ${runner.calls(1)}"
      )

  test(
    "runAutonomous does NOT register an MCP server (autonomous skips bridge)"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ =
        backend.runAutonomous("q", clientSid, AgentConfig.default, workDir)
      assert(
        !os.exists(workDir / ".gemini" / "settings.json"),
        "autonomous must not write a .gemini/settings.json"
      )

  test(
    "runInteractive registers the orca MCP server and folds the ask_user hint"
  ):
    val runner = new SpawnStubCliRunner(List(pendingProcess()))
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ = backend.runInteractive(
        "q",
        clientSid,
        displayPrompt = "q",
        AgentConfig.default,
        workDir,
        outputSchema = None
      )
      val settings = workDir / ".gemini" / "settings.json"
      assert(os.exists(settings), "interactive must register an MCP server")
      assert(
        os.read(settings).contains("orca"),
        s"expected the orca server in settings.json; got: ${os.read(settings)}"
      )
      val finalPrompt = runner.calls.head.last
      assert(
        finalPrompt.contains("ask_user"),
        s"final prompt should fold in the ask_user hint; got: $finalPrompt"
      )

  test(
    "runInteractive with a systemPrompt folds BOTH it and the ask_user hint"
  ):
    val runner = new SpawnStubCliRunner(List(pendingProcess()))
    withBackend(runner): backend =>
      val _ = backend.runInteractive(
        "list files",
        clientSid,
        displayPrompt = "list files",
        AgentConfig.default.copy(systemPrompt = Some("be terse")),
        os.temp.dir(),
        outputSchema = None
      )
      val finalPrompt = runner.calls.head.last
      assert(finalPrompt.contains("be terse"))
      assert(finalPrompt.contains("ask_user"))
      assert(finalPrompt.contains("list files"))

  // sessionExists probes the SERVER id, not the client id: it resolves the
  // client→server mapping first (gemini mints its own id), then scans
  // `--list-sessions` for that server id. A `registerSession` seeds the map.

  private val clientForProbe = SessionId[BackendTag.Gemini.type]("client-uuid")
  private val serverForProbe =
    WireSessionId[BackendTag.Gemini.type]("sess-abc-123")

  test(
    "sessionExists probes the SERVER id: true when it appears in --list-sessions"
  ):
    // clientForProbe ("client-uuid") and serverForProbe ("sess-abc-123") are
    // distinct. The stub stdout contains the server id but NOT the client id, so
    // the returned `true` can only be explained by the code scanning for the
    // server id. A bug that scanned for the client id would return `false`
    // (client id absent from stdout) and the `assert` below would fail.
    val listOutput = "sess-abc-123  2024-01-01T00:00:00"
    // Sanity: ensure client and server ids are truly distinct in the output.
    assert(
      !listOutput.contains(clientForProbe.value),
      "test invariant: --list-sessions stdout must NOT contain the client id"
    )
    val stub = new StubCliRunner(CliResult(0, listOutput, ""))
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      backend.sessions.register(clientForProbe, serverForProbe)
      assert(backend.sessions.exists(clientForProbe))
      // Verify the probe used the correct command.
      val probeArgs = stub.calls.head.args
      assertEquals(
        probeArgs,
        List("gemini", "--list-sessions"),
        s"sessionExists must invoke exactly `gemini --list-sessions`; got: $probeArgs"
      )

  test(
    "sessionExists returns false when there is no client→server mapping"
  ):
    // No registerSession: the client id maps to nothing, so the probe must not
    // run (and must not pass the client id to --list-sessions).
    val stub =
      new StubCliRunner(CliResult(0, "client-uuid  2024-01-01T00:00:00", ""))
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      assert(!backend.sessions.exists(clientForProbe))

  test(
    "sessionExists returns false when the server id is not in the output"
  ):
    val stub =
      new StubCliRunner(CliResult(0, "sess-other  2024-01-01T00:00:00", ""))
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      backend.sessions.register(clientForProbe, serverForProbe)
      assert(!backend.sessions.exists(clientForProbe))

  test(
    "sessionExists returns false when gemini --list-sessions exits non-zero"
  ):
    val stub = new StubCliRunner(CliResult(1, "sess-abc-123", ""))
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      backend.sessions.register(clientForProbe, serverForProbe)
      assert(!backend.sessions.exists(clientForProbe))

  test(
    "sessionExists returns false when the cli runner throws (verifies NonFatal catch)"
  ):
    val stub = new StubCliRunner():
      override def run(
          args: Seq[String],
          stdin: String,
          env: Map[String, String],
          cwd: os.Path
      ): CliResult = throw new RuntimeException("binary not found")
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      backend.sessions.register(clientForProbe, serverForProbe)
      assert(!backend.sessions.exists(clientForProbe))

  test(
    "sessionExists returns false for a malicious server id containing path chars"
  ):
    val maliciousServer =
      WireSessionId[BackendTag.Gemini.type]("../../etc/passwd")
    val stub = new StubCliRunner(CliResult(0, "../../etc/passwd", ""))
    SupervisedBackend.using(new GeminiBackend(stub)): backend =>
      backend.sessions.register(clientForProbe, maliciousServer)
      assert(!backend.sessions.exists(clientForProbe))
