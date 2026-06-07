package orca.tools.gemini

import orca.backend.SupervisedBackend
import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import orca.OrcaFlowException
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class GeminiBackendTest extends munit.FunSuite:

  private def clientSid: SessionId[BackendTag.Gemini.type] =
    SessionId[BackendTag.Gemini.type]("00000000-0000-0000-0000-000000000000")

  private def withBackend[T](runner: SpawnStubCliRunner)(
      body: GeminiBackend => T
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
        backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
      // The returned session id is the client id — the server's sess-42 is
      // mapped internally so subsequent calls resume it.
      assertEquals(result.sessionId, clientSid)
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
        backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
      assertEquals(result.model, Some(Model("gemini-2.5-pro")))

  test("runAutonomous throws when gemini exits without a result event"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"init","session_id":"s"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())

  test("runAutonomous throws with the exit code when gemini exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
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
        backend.runAutonomous("q", clientSid, LlmConfig.default, os.temp.dir())
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
        LlmConfig.default.copy(systemPrompt = Some("be terse")),
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
        backend.runAutonomous("first", clientSid, LlmConfig.default, workDir)
      val _ =
        backend.runAutonomous("again", clientSid, LlmConfig.default, workDir)
      val firstArgs = runner.calls(0)
      val secondArgs = runner.calls(1)
      assert(!firstArgs.contains("--resume"), firstArgs.toString)
      assert(secondArgs.contains("--resume"), secondArgs.toString)
      assert(secondArgs.contains("sess-server-1"), secondArgs.toString)

  test("registerSession lets a follow-up autonomous call resume"):
    val runner = new SpawnStubCliRunner(List(successfulProcess("sess-via-int")))
    withBackend(runner): backend =>
      backend.registerSession(
        clientSid,
        SessionId[BackendTag.Gemini.type]("sess-via-int")
      )
      val _ =
        backend.runAutonomous(
          "after",
          clientSid,
          LlmConfig.default,
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
      val _ = backend.runAutonomous("for A", sidA, LlmConfig.default, workDir)
      val _ = backend.runAutonomous("for B", sidB, LlmConfig.default, workDir)
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
      val _ = backend.runAutonomous("q", clientSid, LlmConfig.default, workDir)
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
        LlmConfig.default,
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
        LlmConfig.default.copy(systemPrompt = Some("be terse")),
        os.temp.dir(),
        outputSchema = None
      )
      val finalPrompt = runner.calls.head.last
      assert(finalPrompt.contains("be terse"))
      assert(finalPrompt.contains("ask_user"))
      assert(finalPrompt.contains("list files"))
