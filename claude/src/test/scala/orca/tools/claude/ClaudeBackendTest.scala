package orca.tools.claude

import orca.backend.{Interaction, SupervisedBackend, SystemPromptComposer}
import orca.backend.mcp.{GitHubMcpServer, RepoMcpServer}
import orca.agents.{
  BackendTag,
  AgentConfig,
  DefaultPrompts,
  SessionId,
  WireSessionId,
  ToolSet,
  onWire
}
import orca.events.OrcaListener
import orca.{OrcaFlowException}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import orca.testkit.{GitRepo, TempDirs}

class ClaudeBackendTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  // Never driven — the closed-latch test throws before reaching a
  // conversation.
  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.Conversation[B]
    )(using ox.Ox): orca.backend.AgentResult[B] =
      throw new UnsupportedOperationException("test stub")

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

  private def withBackend[T](runner: SpawnStubCliRunner)(
      body: ox.Ox ?=> ClaudeBackend => T
  ): T = SupervisedBackend.using(new ClaudeBackend(runner))(body)

  private def freshSid: SessionId[BackendTag.ClaudeCode.type] =
    SessionId[BackendTag.ClaudeCode.type](
      "11111111-1111-1111-1111-111111111111"
    )

  test("runAutonomous invokes claude in stream-json mode (no --mcp-config)"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ =
        backend.runAutonomous(
          "summarize",
          freshSid,
          AgentConfig()
        )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--input-format", "stream-json")))
      assert(args.containsSlice(Seq("--output-format", "stream-json")))
      // No ask_user MCP on the autonomous path.
      assert(!args.contains("--mcp-config"), args)

  test("an MCP config left behind by a hard kill can't reach a commit"):
    val repo = GitRepo.seeded()
    val _ = orca.OrcaDir.ensureCache(repo)
    os.write(ClaudeBackend.mcpConfigPath(repo, freshSid), "{}")
    val _ = os.proc("git", "add", "-A").call(cwd = repo)
    val staged =
      os.proc("git", "diff", "--cached", "--name-only")
        .call(cwd = repo)
        .out
        .text()
    assertEquals(staged, "", "the MCP config must be unstageable")

  test("a read-only autonomous call wires the repo-read MCP server"):
    // The config is read at spawn, not after: the conversation deletes it when
    // the turn finalizes.
    var mcpConfig: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args =>
        mcpConfig =
          Some(os.read(os.Path(args(args.indexOf("--mcp-config") + 1))))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
      assert(mcpConfig.exists(_.contains(RepoMcpServer.ServerName)), mcpConfig)
      val args = runner.calls.head
      assertEquals(
        args(args.indexOf("--allowedTools") + 1),
        ClaudeBackend.RepoToolNames.mkString(",")
      )

  test("the repo-read MCP binding and its config go when the turn finalizes"):
    // A leaked binding holds its Netty event-loop threads and its port for the
    // life of the JVM, and a flow runs hundreds of read-only turns.
    var probe: Option[(String, os.Path)] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args =>
        val config = os.Path(args(args.indexOf("--mcp-config") + 1))
        probe = Some((os.read(config), config))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
    val (configText, configPath) = probe.get
    assert(!os.exists(configPath), configPath)
    val port = """127\.0\.0\.1:(\d+)""".r.findFirstMatchIn(configText).get
    intercept[java.net.ConnectException]:
      val socket = new java.net.Socket()
      try
        socket.connect(
          new java.net.InetSocketAddress("127.0.0.1", port.group(1).toInt),
          1000
        )
      finally socket.close()

  test("a Full autonomous call stands up no MCP server"):
    // The repo reads exist to replace the shell a read-only turn loses; a Full
    // turn still has Bash, so it should pay neither the binding nor the tokens.
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous("x", freshSid, AgentConfig())
      assert(!runner.calls.head.contains("--mcp-config"), runner.calls.head)

  test("a NetworkOnly call also wires the GitHub reads"):
    var mcpConfig: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args =>
        mcpConfig =
          Some(os.read(os.Path(args(args.indexOf("--mcp-config") + 1))))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(tools = ToolSet.NetworkOnly)
      )
      assert(
        mcpConfig.exists(_.contains(GitHubMcpServer.ServerName)),
        mcpConfig
      )
      val args = runner.calls.head
      assertEquals(
        args(args.indexOf("--allowedTools") + 1),
        (ClaudeBackend.RepoToolNames ++ ClaudeBackend.GitHubToolNames ++
          ClaudeBackend.DefaultNetworkTools).mkString(",")
      )

  test("a read-only interactive turn pre-approves ask_user"):
    // The read-only tiers ignore `autoApprove`, which is the route
    // `autoApproveAlso` takes, so ask_user has to reach --allowedTools through
    // `mcpTools` or the turn loses its only channel to the user.
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val conv = backend.runInteractive(
        "x",
        freshSid,
        "x",
        AgentConfig(tools = ToolSet.ReadOnly),
        None
      )
      try
        val args = runner.calls.head
        assert(
          args(args.indexOf("--allowedTools") + 1)
            .contains(ClaudeBackend.AskUserToolName),
          args
        )
      finally conv.cancel()

  test("a ReadOnly call gets no GitHub reads"):
    // ReadOnly is the reviewers' tier and must stay network-free; the GitHub
    // tools reach the network host-side.
    var mcpConfig: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args =>
        mcpConfig =
          Some(os.read(os.Path(args(args.indexOf("--mcp-config") + 1))))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
      assert(
        !mcpConfig.exists(_.contains(GitHubMcpServer.ServerName)),
        mcpConfig
      )

  test("NetworkOnly autonomous call allows the default network tools"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig().copy(tools = ToolSet.NetworkOnly)
      )
      val args = runner.calls.head
      assertEquals(
        args(args.indexOf("--tools") + 1),
        "Read,Grep,Glob,Skill,WebFetch,WebSearch"
      )

  test("withNetworkTools rejects the old command-scoped syntax"):
    // --tools drops a name it doesn't recognise silently, so a flow script
    // still passing `Bash(gh api:*)` would grant nothing and say nothing.
    val thrown = intercept[IllegalArgumentException]:
      new ClaudeBackend(new SpawnStubCliRunner(Nil))
        .withNetworkTools(Seq("WebFetch", "Bash(gh api:*)"))
    assert(thrown.getMessage.contains("Bash(gh api:*)"), thrown.getMessage)

  test("withNetworkTools overrides the default network tools"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    SupervisedBackend.using(
      new ClaudeBackend(runner).withNetworkTools(Seq("WebFetch"))
    ): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig().copy(tools = ToolSet.NetworkOnly)
      )
      val args = runner.calls.head
      assertEquals(
        args(args.indexOf("--tools") + 1),
        "Read,Grep,Glob,Skill,WebFetch"
      )

  test(
    "a withNetworkTools sibling shares the parent's closed latch"
  ):
    // withNetworkTools is the one builder that swaps in a genuinely NEW
    // ClaudeBackend instance rather than reusing the caller's; the new instance
    // must still share the parent's closedFlag, or a handle derived while the
    // flow was open and used after the leading agent's flow closed would bypass
    // the use-after-close guard. `run` never reaches the (empty) stub runner:
    // the guard must throw first.
    val backend = new ClaudeBackend(new SpawnStubCliRunner(Nil))
    val agent = new DefaultClaudeAgent(
      backend,
      AgentConfig(),
      DefaultPrompts,
      OrcaListener.noop,
      stubInteraction
    )
    val derived = agent.withNetworkTools(Seq("WebFetch"))
    agent.close() // latches the shared backend, not just `agent`'s own handle
    val thrown = intercept[OrcaFlowException]:
      derived.run("prompt")
    assertEquals(thrown.getMessage, orca.backend.AgentBackend.ClosedMessage)

  test("a withNetworkTools sibling shares the parent's enforcement notices"):
    // The same rule as the closed latch above, for the other value a sibling
    // must not get its own copy of: with a fresh log it would repeat every
    // notice the parent already gave. Pinned structurally rather than by
    // running a turn — claude's cells are `Hard` throughout, so no claude turn
    // can make the notice fire at all.
    val backend = new ClaudeBackend(new SpawnStubCliRunner(Nil))
    assert(
      backend.withNetworkTools(Seq("WebFetch")).enforcementNotice eq
        backend.enforcementNotice
    )

  test("claude declares Tool structured-output mode"):
    // The declaration behind the prompt's delivery instruction: --json-schema
    // (asserted below) makes the CLI inject a StructuredOutput tool, so the
    // instruction must ask for that tool call, not raw reply text.
    val backend = new ClaudeBackend(new SpawnStubCliRunner(Nil))
    assertEquals(
      backend.structuredOutputMode,
      orca.agents.StructuredOutputMode.Tool
    )

  test(
    "runAutonomous passes --json-schema when an output schema is supplied"
  ):
    // Autonomous structured calls get claude-side schema enforcement on top
    // of the prompt-template contract. `JsonSchemaGen` produces
    // OpenAI-strict schemas so claude accepts them.
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(),
        outputSchema = Some("""{"type":"object"}""")
      )
      val args = runner.calls.head
      assert(
        args.containsSlice(Seq("--json-schema", """{"type":"object"}""")),
        s"autonomous must pass --json-schema; got: $args"
      )

  test("runAutonomous parses session id, output, usage, and cost"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val result =
        backend.runAutonomous("x", freshSid, AgentConfig())
      assertEquals(WireSessionId.value(result.wireId), "sess-123")
      assertEquals(result.output, "hello world")
      assertEquals(result.usage.inputTokens, 10L)
      assertEquals(result.usage.outputTokens, 5L)
      assertEquals(result.usage.cost, Some(BigDecimal("0.0012")))

  test("runAutonomous throws when the result message reports is_error"):
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
        backend.runAutonomous("x", freshSid, AgentConfig())

  test("runAutonomous throws when the subprocess exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(2)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runAutonomous("x", freshSid, AgentConfig())

  test(
    "runAutonomous passes a --append-system-prompt-file pointing at the config's prompt"
  ):
    var promptText: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args => promptText = Some(readSystemPrompt(args))
    )
    withBackend(runner): backend =>
      val config = AgentConfig(systemPrompt = Some("you are a poet"))
      val _ = backend.runAutonomous("x", freshSid, config)
      // The configured prompt leads; SystemPromptComposer appends the standing
      // rules after it.
      assert(promptText.exists(_.startsWith("you are a poet")), promptText)

  test("a read-only turn still gets the turn-boundary rule in its prompt file"):
    // Read-only turns compose no git rule, so this is the one that must arrive.
    val promptText = readOnlySystemPrompt()
    assert(
      promptText.exists(
        _.contains(SystemPromptComposer.BackgroundWorkAbandonedAtTurnEnd)
      ),
      promptText
    )
    assert(
      !promptText.exists(_.contains(SystemPromptComposer.RuntimeOwnsGit)),
      promptText
    )

  test("a read-only turn is told about the repo-read MCP tools"):
    // Without the hint the agent has no reason to look for tools that replace
    // the shell it no longer has.
    val promptText = readOnlySystemPrompt()
    assert(promptText.exists(_.contains(RepoMcpServer.Hint)), promptText)

  /** The system-prompt file text a `ToolSet.ReadOnly` autonomous turn spawns
    * with.
    */
  private def readOnlySystemPrompt(): Option[String] =
    var promptText: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args => promptText = Some(readSystemPrompt(args))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
    promptText

  test("the system-prompt temp file is deleted when the turn finalizes"):
    // `os.temp`'s deleteOnExit only fires at JVM shutdown; a flow runs hundreds
    // of turns, so the file must go at turn end.
    var promptFile: Option[os.Path] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args => promptFile = Some(systemPromptPath(args))
    )
    withBackend(runner): backend =>
      val _ = backend.runAutonomous("x", freshSid, AgentConfig())
      assert(promptFile.exists(p => !os.exists(p)), promptFile)

  /** Path of the file `--append-system-prompt-file` points at, failing on a
    * missing flag rather than reading whatever argument sits at index 0.
    */
  private def systemPromptPath(args: List[String]): os.Path =
    val idx = args.indexOf("--append-system-prompt-file")
    require(idx >= 0, s"no --append-system-prompt-file in $args")
    os.Path(args(idx + 1))

  /** Read that file at spawn time — the conversation deletes it when the turn
    * finalizes, so it is gone by the time `runAutonomous` returns.
    */
  private def readSystemPrompt(args: List[String]): String =
    os.read(systemPromptPath(args))

  test(
    "first runAutonomous call uses --session-id; second with the same id uses --resume"
  ):
    val sid = SessionId[BackendTag.ClaudeCode.type](
      "22222222-2222-2222-2222-222222222222"
    )
    val runner = new SpawnStubCliRunner(
      List(successfulProcess(), successfulProcess())
    )
    withBackend(runner): backend =>
      val _ =
        backend.runAutonomous("first", sid, AgentConfig())
      val _ =
        backend.runAutonomous("again", sid, AgentConfig())
      val first = runner.calls(0)
      val second = runner.calls(1)
      assert(
        first.containsSlice(Seq("--session-id", SessionId.value(sid))),
        first
      )
      assert(
        second.containsSlice(Seq("--resume", SessionId.value(sid))),
        second
      )

  test(
    "registerSession (rehydrate on resume) makes the first call use --resume, not --session-id"
  ):
    // Claude's sessions are durable on disk, so a resumed run re-claims the
    // recorded id via `registerSession` (what `rehydrateSessions` calls). The
    // very first call in THIS process must then `--resume` the existing session
    // rather than re-create it with `--session-id` (which the CLI rejects as
    // "already in use").
    val sid = SessionId[BackendTag.ClaudeCode.type](
      "44444444-4444-4444-4444-444444444444"
    )
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      backend.sessions.register(sid, sid.onWire)
      val _ =
        backend.runAutonomous(
          "continue",
          sid,
          AgentConfig()
        )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--resume", SessionId.value(sid))), args)
      assert(!args.contains("--session-id"), args)

  test(
    "resumeWireId reflects the claim so the runtime records the resumable id"
  ):
    // `resumeWireId` is the source the runtime reads to write the resume wire id
    // into the progress log; without the claim, resume would re-create the
    // session.
    val sid = SessionId[BackendTag.ClaudeCode.type](
      "55555555-5555-5555-5555-555555555555"
    )
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      assertEquals(backend.sessions.persistableWireId(sid), None) // unclaimed
      val _ =
        backend.runAutonomous("hi", sid, AgentConfig())
      val wire: WireSessionId[BackendTag.ClaudeCode.type] = sid.onWire
      assertEquals(
        backend.sessions.persistableWireId(sid),
        Some(wire)
      ) // claimed → persistable

  test(
    "failed first call leaves the session unclaimed; retry still uses --session-id"
  ):
    // The session mapping is recorded only after `new ClaudeConversation`
    // succeeds, so a first call that throws (e.g. is_error from the result
    // message) doesn't wedge the bookkeeping.
    val sid = SessionId[BackendTag.ClaudeCode.type](
      "33333333-3333-3333-3333-333333333333"
    )
    val failing = new FakePipedCliProcess()
    failing.enqueueStdout(
      """{"type":"system","subtype":"init","session_id":"s","model":"claude-haiku-4-5"}"""
    )
    failing.enqueueStdout(
      """{"type":"result","subtype":"error","session_id":"s","result":"denied","usage":{"input_tokens":0,"output_tokens":0},"is_error":true}"""
    )
    failing.closeStdout()
    failing.closeStderr()
    failing.sendSigInt()
    val runner = new SpawnStubCliRunner(List(failing, successfulProcess()))
    withBackend(runner): backend =>
      val _ = intercept[OrcaFlowException]:
        backend.runAutonomous("first", sid, AgentConfig())
      val _ =
        backend.runAutonomous("retry", sid, AgentConfig())
      val second = runner.calls(1)
      assert(
        second.containsSlice(Seq("--session-id", SessionId.value(sid))),
        s"retry after failure must re-claim with --session-id; got: $second"
      )

  test(
    "willContinue returns true when the id is claimed and the transcript exists"
  ):
    val tmpProjects = TempDirs.dir()
    val cwd = TempDirs.dir()
    val slug = ClaudeBackend.cwdSlug(cwd)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        workDir = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(backend.sessions.willContinue(freshSid))

  test(
    "workDir is shared, by construction, between the actual spawn cwd and the session-existence probe"
  ):
    // `workDir` is fixed once at construction and BOTH the probe (via
    // `sessions.willContinue`) and the real subprocess spawn (via
    // `runAutonomous` → `cli.spawnPiped(..., cwd = workDir)`) read that SAME
    // field, so no per-call value can drift out of sync. A backend constructed
    // with a worktree-style `workDir` (!= the process cwd) must probe AND spawn
    // under that same directory.
    val tmpProjects = TempDirs.dir()
    val flowWorkDir =
      TempDirs.dir() // stands in for a worktree checkout, != os.pwd
    assert(
      flowWorkDir != os.pwd,
      "test setup requires a workDir distinct from the process cwd"
    )
    val slug = ClaudeBackend.cwdSlug(flowWorkDir)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")

    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    SupervisedBackend.using(
      new ClaudeBackend(
        runner,
        projectsDir = tmpProjects,
        workDir = flowWorkDir
      )
    ): backend =>
      assertEquals(backend.workDir, flowWorkDir)
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(
        backend.sessions.willContinue(freshSid),
        "probe must read the constructor's workDir"
      )
      val _ = backend.runAutonomous("x", freshSid, AgentConfig())
      assertEquals(
        runner.spawnCalls.head.cwd,
        flowWorkDir,
        "spawn must use the SAME workDir the probe just read"
      )

    // The bare-construction default (`workDir = os.pwd`) probes the WRONG
    // directory when the process cwd differs from the flow's workDir —
    // exactly the bug `ClaudeAgents.default` must avoid by passing
    // `workDir = wiring.workDir` explicitly.
    SupervisedBackend.using(
      new ClaudeBackend(new SpawnStubCliRunner(Nil), projectsDir = tmpProjects)
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.willContinue(freshSid))

  test(
    "willContinue returns false when the transcript is present but never claimed"
  ):
    // Existence is only answered for an id the bookkeeping knows (claimed this
    // run or rehydrated). A stray transcript for an id we never claimed reports
    // false — outcome-preserving, since dispatch would say `Fresh` and the CLI
    // would refuse the duplicate `--session-id` anyway.
    val tmpProjects = TempDirs.dir()
    val cwd = TempDirs.dir()
    val slug = ClaudeBackend.cwdSlug(cwd)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        workDir = cwd
      )
    ): backend =>
      assert(!backend.sessions.willContinue(freshSid))

  test(
    "willContinue returns false when the id is claimed but the transcript is absent"
  ):
    val tmpProjects = TempDirs.dir()
    val cwd = TempDirs.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        workDir = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.willContinue(freshSid))

  test("willContinue returns false when the projects dir is absent"):
    val missing = TempDirs.dir() / "no-such-dir"
    val cwd = TempDirs.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = missing,
        workDir = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.willContinue(freshSid))

  test(
    "willContinue returns false for a malicious id with path traversal chars"
  ):
    val tmpProjects = TempDirs.dir()
    val cwd = TempDirs.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        workDir = cwd
      )
    ): backend =>
      val maliciousId =
        SessionId[BackendTag.ClaudeCode.type]("../../etc/passwd")
      // `register`'s SessionId.isSafe guard must refuse to record the
      // traversal id in the first place, so `willContinue` finds no mapping
      // and never reaches the probe.
      backend.sessions.register(maliciousId, maliciousId.onWire)
      assert(!backend.sessions.willContinue(maliciousId))
