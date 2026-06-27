package orca.tools.pi

import orca.backend.SystemPromptComposer
import orca.events.Usage
import orca.agents.{BackendTag, AgentConfig, Model, SessionId, ToolSet}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class PiBackendTest extends munit.FunSuite:

  private def sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("00000000-0000-0000-0000-000000000000")

  private def successfulProcess(
      message: String = "hello",
      inputTokens: Long = 1L,
      outputTokens: Long = 2L
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"response","id":"orca-prompt","command":"prompt","success":true}"""
    )
    p.enqueueStdout(
      s"""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$message"}],"usage":{"input":$inputTokens,"output":$outputTokens},"model":"pi-model"}}"""
    )
    p.enqueueStdout("""{"type":"agent_end","messages":[]}""")
    p

  test(
    "runAutonomous starts pi RPC, writes prompt command, and returns stable session"
  ):
    val process = successfulProcess("answer", 10L, 5L)
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)
    val workDir = os.temp.dir()

    val result =
      backend.runAutonomous("do it", sid, AgentConfig.default, workDir)

    assertEquals(result.sessionId, sid)
    assertEquals(result.output, "answer")
    assertEquals(result.usage, Usage(10L, 5L, None))
    assertEquals(result.model.map(_.name), Some("pi-model"))

    val call = runner.spawnCalls.head
    assertEquals(call.cwd, workDir)
    assertEquals(call.pipeStderr, true)
    assert(call.args.containsSlice(Seq("pi", "--mode", "rpc")), call.args)
    // A fresh session opens a dir named for the session id, without --continue.
    val dir = call.args(call.args.indexOf("--session-dir") + 1)
    assert(dir.endsWith(SessionId.value(sid)), dir)
    assert(!call.args.contains("--continue"), call.args)
    assert(process.writes.exists(_.contains("\"type\":\"prompt\"")))
    assert(process.writes.exists(_.contains("do it")))

  test("a second turn on the same session resumes with --continue"):
    val runner =
      new SpawnStubCliRunner(List(successfulProcess(), successfulProcess()))
    val backend = new PiBackend(runner)
    val workDir = os.temp.dir()

    val _ = backend.runAutonomous("one", sid, AgentConfig.default, workDir)
    val _ = backend.runAutonomous("two", sid, AgentConfig.default, workDir)

    val Seq(first, second) = runner.spawnCalls.take(2): @unchecked
    assert(!first.args.contains("--continue"), first.args)
    assert(second.args.contains("--continue"), second.args)
    // Same session dir both times.
    def dirOf(a: List[String]) = a(a.indexOf("--session-dir") + 1)
    assertEquals(dirOf(first.args), dirOf(second.args))

  test("a failed first turn leaves the session fresh, not --continue"):
    val failing = new FakePipedCliProcess(initiallyAlive = false)
    failing.closeStdout() // EOF before agent_end → the turn fails
    failing.closeStderr()
    val runner = new SpawnStubCliRunner(List(failing, successfulProcess()))
    val backend = new PiBackend(runner)
    val workDir = os.temp.dir()

    val _ = intercept[Exception](
      backend.runAutonomous("one", sid, AgentConfig.default, workDir)
    )
    val _ = backend.runAutonomous("two", sid, AgentConfig.default, workDir)

    val Seq(first, second) = runner.spawnCalls.take(2): @unchecked
    assert(!first.args.contains("--continue"), first.args)
    // The failed first turn wasn't committed, so the retry starts fresh.
    assert(!second.args.contains("--continue"), second.args)

  test("model and autonomous read-only config map to Pi flags"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val _ = backend.runAutonomous(
      "q",
      sid,
      AgentConfig.default.copy(
        model = Some(Model("anthropic/claude-sonnet")),
        tools = ToolSet.ReadOnly
      ),
      os.temp.dir()
    )

    val args = runner.calls.head
    assert(args.containsSlice(Seq("--model", "anthropic/claude-sonnet")), args)
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls")), args)
    assert(!args.contains("--extension"), args)

  test("interactive read-only config includes ask_user extension and tool"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    // The conversation forks its workers into the surrounding Ox scope, so it
    // must be created AND consumed within the same `supervised` block.
    ox.supervised:
      val conv = backend.runInteractive(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig.default.copy(tools = ToolSet.ReadOnly),
        os.temp.dir(),
        outputSchema = Some("{}")
      )
      assert(conv.canAskUser)
      assertEquals(conv.outputSchema, Some("{}"))

      val args = runner.calls.head
      assert(
        args.containsSlice(Seq("--tools", "read,grep,find,ls,ask_user")),
        args
      )
      assert(args.contains("--extension"), args)

      val _ = conv.events.toList
      val _ = conv.awaitResult()

  test(
    "interactive system prompt file contains configured prompt, hint, and git rule"
  ):
    val process = new FakePipedCliProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    // The conversation forks its workers into the surrounding Ox scope, so it
    // must be created AND consumed within the same `supervised` block.
    ox.supervised:
      val conv = backend.runInteractive(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig.default.copy(systemPrompt = Some("be terse")),
        os.temp.dir(),
        outputSchema = None
      )

      val args = runner.calls.head
      val promptFile = args(args.indexOf("--append-system-prompt") + 1)
      val promptText = os.read(os.Path(promptFile))
      assert(promptText.contains("be terse"), promptText)
      assert(promptText.contains(PiAskUserExtension.Hint), promptText)
      assert(
        promptText.contains(SystemPromptComposer.RuntimeOwnsGit),
        promptText
      )

      val extensionFile = os.Path(args(args.indexOf("--extension") + 1))
      assert(os.exists(extensionFile))

      process.enqueueStdout(
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
      )
      process.enqueueStdout("""{"type":"agent_end","messages":[]}""")
      val _ = conv.events.toList
      val _ = conv.awaitResult()
      assert(!os.exists(os.Path(promptFile)))
      assert(!os.exists(extensionFile))

  test("self-managed git suppresses the runtime git rule"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val _ = backend.runAutonomous(
      "q",
      sid,
      AgentConfig.default.copy(selfManagedGit = true),
      os.temp.dir()
    )

    val args = runner.calls.head
    assert(!args.contains("--append-system-prompt"), args)

  test("sessionExists always returns false (Pi has no server-side probe)"):
    val backend = new PiBackend(new SpawnStubCliRunner(Nil))
    assert(!backend.sessionExists(sid))
