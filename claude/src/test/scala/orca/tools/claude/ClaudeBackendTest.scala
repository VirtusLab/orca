package orca.tools.claude

import orca.backend.{Interaction, SupervisedBackend}
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

class ClaudeBackendTest extends munit.FunSuite:

  // LLM `run` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  // Never driven — the closed-latch test throws before reaching a
  // conversation.
  private val stubInteraction: Interaction = new Interaction:
    val listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        conversation: orca.backend.Conversation[B]
    ): orca.backend.AgentResult[B] =
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
          AgentConfig(),
          os.temp.dir()
        )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--input-format", "stream-json")))
      assert(args.containsSlice(Seq("--output-format", "stream-json")))
      // No ask_user MCP on the autonomous path.
      assert(!args.contains("--mcp-config"), args)

  test("NetworkOnly autonomous call pre-approves the default network tools"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig().copy(tools = ToolSet.NetworkOnly),
        os.temp.dir()
      )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--permission-mode", "plan")), args)
      val allowed = args(args.indexOf("--allowedTools") + 1)
      assert(allowed.contains("WebFetch"), allowed)
      assert(allowed.contains("Bash(gh api:*)"), allowed)

  test("withNetworkTools overrides the default allowlist"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    SupervisedBackend.using(
      new ClaudeBackend(runner).withNetworkTools(Seq("WebFetch"))
    ): backend =>
      val _ = backend.runAutonomous(
        "x",
        freshSid,
        AgentConfig().copy(tools = ToolSet.NetworkOnly),
        os.temp.dir()
      )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--allowedTools", "WebFetch")), args)

  test(
    "a withNetworkTools sibling shares the parent's closed latch (Epic 7.5)"
  ):
    // withNetworkTools is the one builder that swaps in a genuinely NEW
    // ClaudeBackend instance rather than reusing the caller's (every other
    // builder — withConfig/withModel/opus/withName/… — goes through
    // BaseAgent.copyTool, which keeps the same backend). Before this fix each
    // fresh instance got its own closedFlag, so a handle derived via
    // `agent.withNetworkTools(...)` while the flow was open and used AFTER
    // the leading agent's flow closed would silently bypass the Epic 7.5
    // use-after-close guard. `run` never reaches the (empty) stub runner: the
    // guard must throw first.
    val backend = new ClaudeBackend(new SpawnStubCliRunner(Nil))
    val agent = new DefaultClaudeAgent(
      backend,
      AgentConfig(),
      DefaultPrompts,
      os.temp.dir(),
      OrcaListener.noop,
      stubInteraction
    )
    val derived = agent.withNetworkTools(Seq("WebFetch"))
    agent.close() // latches the shared backend, not just `agent`'s own handle
    val thrown = intercept[OrcaFlowException]:
      derived.autonomous.run("prompt")
    assertEquals(thrown.getMessage, orca.backend.AgentBackend.ClosedMessage)

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
        os.temp.dir(),
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
        backend.runAutonomous("x", freshSid, AgentConfig(), os.temp.dir())
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
        backend.runAutonomous("x", freshSid, AgentConfig(), os.temp.dir())

  test("runAutonomous throws when the subprocess exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(2)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runAutonomous("x", freshSid, AgentConfig(), os.temp.dir())

  test(
    "runAutonomous passes a --append-system-prompt-file pointing at the config's prompt"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val config = AgentConfig(systemPrompt = Some("you are a poet"))
      val _ = backend.runAutonomous("x", freshSid, config, os.temp.dir())
      val args = runner.calls.head
      val flagIdx = args.indexOf("--append-system-prompt-file")
      assert(flagIdx >= 0, s"expected the prompt-file flag in args; got: $args")
      val path = os.Path(args(flagIdx + 1))
      // The configured prompt leads; SystemPromptComposer appends the
      // always-on runtime-owns-git rule on write-capable turns.
      assert(
        os.read(path).startsWith("you are a poet"),
        s"expected the configured prompt first; got: ${os.read(path)}"
      )

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
        backend.runAutonomous("first", sid, AgentConfig(), os.temp.dir())
      val _ =
        backend.runAutonomous("again", sid, AgentConfig(), os.temp.dir())
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
    // Regression for the cross-process resume bug: claude's sessions are
    // durable on disk, so a resumed run re-claims the recorded id via
    // `registerSession` (what `rehydrateSessions` calls). The very first call in
    // THIS process must then `--resume` the existing session rather than
    // re-create it with `--session-id` (which the CLI rejects as "already in
    // use"). Before the fix, claude wired neither hook, so it always re-created.
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
          AgentConfig(),
          os.temp.dir()
        )
      val args = runner.calls.head
      assert(args.containsSlice(Seq("--resume", SessionId.value(sid))), args)
      assert(!args.contains("--session-id"), args)

  test(
    "resumeWireId reflects the claim so the runtime records the resumable id"
  ):
    // `resumeWireId` is the source the runtime reads to write the resume wire id
    // into the progress log; without it (the old default `None`) the claim was
    // never persisted and resume re-created the session.
    val sid = SessionId[BackendTag.ClaudeCode.type](
      "55555555-5555-5555-5555-555555555555"
    )
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      assertEquals(backend.sessions.persistableWireId(sid), None) // unclaimed
      val _ =
        backend.runAutonomous("hi", sid, AgentConfig(), os.temp.dir())
      val wire: WireSessionId[BackendTag.ClaudeCode.type] = sid.onWire
      assertEquals(
        backend.sessions.persistableWireId(sid),
        Some(wire)
      ) // claimed → persistable

  test(
    "failed first call leaves the session unclaimed; retry still uses --session-id"
  ):
    // `sessions.commitSuccess` runs only after `new ClaudeConversation`
    // succeeds, so a first call that throws (e.g. is_error from the result
    // message) doesn't wedge the registry. Pins the post-success ordering
    // against regressions back to mark-then-spawn.
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
        backend.runAutonomous("first", sid, AgentConfig(), os.temp.dir())
      val _ =
        backend.runAutonomous("retry", sid, AgentConfig(), os.temp.dir())
      val second = runner.calls(1)
      assert(
        second.containsSlice(Seq("--session-id", SessionId.value(sid))),
        s"retry after failure must re-claim with --session-id; got: $second"
      )

  test(
    "sessionExists returns true when the id is claimed and the transcript exists"
  ):
    val tmpProjects = os.temp.dir()
    val cwd = os.temp.dir()
    val slug = ClaudeBackend.cwdSlug(cwd)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        cwdForProbe = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(backend.sessions.exists(freshSid))

  test(
    "sessionExists probes the workDir agents actually spawn with, not the process cwd (worktree flows)"
  ):
    // Pins finding 7.5: `cwdForProbe` must track the flow's per-call workDir
    // (what `DefaultFlowContext.withDefaults` now passes), not the `os.pwd`
    // default — in a worktree flow those differ, and probing the wrong
    // directory silently reports false, causing a pointless re-seed on every
    // resume. Two backends share the same transcript location and differ
    // only in `cwdForProbe`: only the one pointed at the actual spawn workDir
    // sees it.
    val tmpProjects = os.temp.dir()
    val spawnWorkDir =
      os.temp.dir() // stands in for a worktree checkout, != os.pwd
    assert(
      spawnWorkDir != os.pwd,
      "test setup requires a workDir distinct from the process cwd"
    )
    val slug = ClaudeBackend.cwdSlug(spawnWorkDir)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")

    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        cwdForProbe = spawnWorkDir
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(backend.sessions.exists(freshSid))

    // The bare-construction default (`cwdForProbe = os.pwd`) probes the
    // WRONG directory when the process cwd differs from the flow's
    // workDir — exactly the bug `DefaultFlowContext.withDefaults` must avoid
    // by passing `cwdForProbe = workDir` explicitly.
    SupervisedBackend.using(
      new ClaudeBackend(new SpawnStubCliRunner(Nil), projectsDir = tmpProjects)
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.exists(freshSid))

  test(
    "sessionExists returns false when the transcript is present but never claimed"
  ):
    // Registry gate: existence is only answered for an id the registry knows
    // (claimed this run or rehydrated). A stray transcript for an id we never
    // claimed reports false — outcome-preserving, since dispatch would say
    // `Fresh` and the CLI would refuse the duplicate `--session-id` anyway.
    val tmpProjects = os.temp.dir()
    val cwd = os.temp.dir()
    val slug = ClaudeBackend.cwdSlug(cwd)
    os.makeDir.all(tmpProjects / slug)
    os.write(tmpProjects / slug / s"${SessionId.value(freshSid)}.jsonl", "")
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        cwdForProbe = cwd
      )
    ): backend =>
      assert(!backend.sessions.exists(freshSid))

  test(
    "sessionExists returns false when the id is claimed but the transcript is absent"
  ):
    val tmpProjects = os.temp.dir()
    val cwd = os.temp.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        cwdForProbe = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.exists(freshSid))

  test("sessionExists returns false when the projects dir is absent"):
    val missing = os.temp.dir() / "no-such-dir"
    val cwd = os.temp.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = missing,
        cwdForProbe = cwd
      )
    ): backend =>
      backend.sessions.register(freshSid, freshSid.onWire)
      assert(!backend.sessions.exists(freshSid))

  test(
    "sessionExists returns false for a malicious id with path traversal chars"
  ):
    val tmpProjects = os.temp.dir()
    val cwd = os.temp.dir()
    SupervisedBackend.using(
      new ClaudeBackend(
        new SpawnStubCliRunner(Nil),
        projectsDir = tmpProjects,
        cwdForProbe = cwd
      )
    ): backend =>
      val maliciousId =
        SessionId[BackendTag.ClaudeCode.type]("../../etc/passwd")
      // `register`'s SessionId.isSafe guard must refuse to record the
      // traversal id in the first place, so `exists` finds no mapping and
      // never reaches the probe.
      backend.sessions.register(maliciousId, maliciousId.onWire)
      assert(!backend.sessions.exists(maliciousId))
