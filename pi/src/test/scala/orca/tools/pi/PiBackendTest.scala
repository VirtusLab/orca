package orca.tools.pi

import orca.OrcaDir
import orca.backend.SystemPromptComposer
import orca.testkit.Usages.usage
import orca.agents.{
  BackendTag,
  AgentConfig,
  Model,
  SessionId,
  WireSessionId,
  ToolSet,
  onWire
}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}
import orca.testkit.TempDirs

import java.time.Instant

class PiBackendTest extends munit.FunSuite:

  private def sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("00000000-0000-0000-0000-000000000000")

  /** A backend on a throwaway `workDir` — Pi's session dirs live under
    * `<workDir>/.orca/cache/pi-sessions/`, so the `os.pwd` default would write
    * them into the repo. Tests that assert on the working dir itself construct
    * the backend directly with their own.
    */
  private def backendWith(runner: SpawnStubCliRunner): PiBackend =
    new PiBackend(runner, workDir = TempDirs.dir())

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
    val workDir = TempDirs.dir()
    val backend = new PiBackend(runner, workDir = workDir)

    val result =
      backend.runAutonomous("do it", sid, AgentConfig())

    val wire: WireSessionId[BackendTag.Pi.type] = sid.onWire
    assertEquals(result.wireId, wire)
    assertEquals(result.output, "answer")
    assertEquals(result.usage, usage(10L, 5L))
    assertEquals(result.model.map(_.name), Some("pi-model"))

    val call = runner.spawnCalls.head
    assertEquals(call.cwd, workDir)
    assertEquals(call.pipeStderr, true)
    assert(call.args.containsSlice(Seq("pi", "--mode", "rpc")), call.args)
    // A fresh session opens a durable dir named for the session id, without
    // --continue.
    val dir = call.args(call.args.indexOf("--session-dir") + 1)
    assertEquals(
      os.Path(dir),
      OrcaDir.piSessionsPath(workDir) / SessionId.value(sid)
    )
    assert(!call.args.contains("--continue"), call.args)
    assert(process.writes.exists(_.contains("\"type\":\"prompt\"")))
    assert(process.writes.exists(_.contains("do it")))

  test("a second turn on the same session resumes with --continue"):
    val runner =
      new SpawnStubCliRunner(List(successfulProcess(), successfulProcess()))
    val backend = backendWith(runner)

    val _ = backend.runAutonomous("one", sid, AgentConfig())
    val _ = backend.runAutonomous("two", sid, AgentConfig())

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
    val backend = backendWith(runner)

    val _ = intercept[Exception](
      backend.runAutonomous("one", sid, AgentConfig())
    )
    val _ = backend.runAutonomous("two", sid, AgentConfig())

    val Seq(first, second) = runner.spawnCalls.take(2): @unchecked
    assert(!first.args.contains("--continue"), first.args)
    // The failed first turn wasn't committed, so the retry starts fresh.
    assert(!second.args.contains("--continue"), second.args)

  test("model and autonomous read-only config map to Pi flags"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = backendWith(runner)

    val _ = backend.runAutonomous(
      "q",
      sid,
      AgentConfig().copy(
        model = Some(Model("anthropic/claude-sonnet")),
        tools = ToolSet.ReadOnly
      )
    )

    val args = runner.calls.head
    assert(args.containsSlice(Seq("--model", "anthropic/claude-sonnet")), args)
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls")), args)
    assert(!args.contains("--extension"), args)

  test("an autonomous turn's system prompt carries no ask_user hint"):
    // The hint and the ask_user extension share one `mode.isInteractive` gate,
    // so an autonomous turn must get neither.
    val promptText = autonomousSystemPrompt(AgentConfig())
    // The positive half matters: a prompt that never composed at all would
    // satisfy "carries no hint" too.
    assert(
      promptText.exists(_.contains(SystemPromptComposer.RuntimeOwnsGit)),
      promptText
    )
    assert(
      promptText.exists(!_.contains(PiAskUserExtension.Hint)),
      promptText
    )

  test("a read-only turn's system prompt file carries the read-only rule"):
    val promptText = autonomousSystemPrompt(
      AgentConfig(tools = ToolSet.ReadOnly)
    )
    assert(
      promptText.exists(_.contains(SystemPromptComposer.ReadOnlyTurn)),
      promptText
    )

  /** The system-prompt file text an autonomous turn under `config` spawns with,
    * captured at spawn time — the file is gone once the turn finishes.
    */
  private def autonomousSystemPrompt(config: AgentConfig): Option[String] =
    var promptText: Option[String] = None
    val runner = new SpawnStubCliRunner(
      List(successfulProcess()),
      onSpawn = args => promptText = Some(readSystemPrompt(args))
    )
    val _ = backendWith(runner).runAutonomous("q", sid, config)
    promptText

  test("interactive read-only config includes ask_user extension and tool"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = backendWith(runner)

    // The conversation forks its workers into the surrounding Ox scope, so it
    // must be created AND consumed within the same `supervised` block.
    ox.supervised:
      val conv = backend.runInteractive(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig().copy(tools = ToolSet.ReadOnly),
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
    "interactive system prompt file contains configured prompt, hint, and standing rules"
  ):
    val process = new FakePipedCliProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = backendWith(runner)

    // The conversation forks its workers into the surrounding Ox scope, so it
    // must be created AND consumed within the same `supervised` block.
    ox.supervised:
      val conv = backend.runInteractive(
        "q",
        sid,
        displayPrompt = "q",
        AgentConfig().copy(systemPrompt = Some("be terse")),
        outputSchema = None
      )

      val args = runner.calls.head
      val promptText = readSystemPrompt(args)
      assert(promptText.contains("be terse"), promptText)
      assert(promptText.contains(PiAskUserExtension.Hint), promptText)
      // One standing rule is enough: they reach the file in a single write, and
      // which rules compose is `SystemPromptComposerTest`'s.
      assert(
        promptText.contains(
          SystemPromptComposer.BackgroundWorkAbandonedAtTurnEnd
        ),
        promptText
      )

      val promptFile = os.Path(args(args.indexOf("--append-system-prompt") + 1))
      val extensionFile = os.Path(args(args.indexOf("--extension") + 1))
      assert(os.exists(extensionFile))

      process.enqueueStdout(
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
      )
      process.enqueueStdout("""{"type":"agent_end","messages":[]}""")
      val _ = conv.events.toList
      val _ = conv.awaitResult()
      assert(!os.exists(promptFile))
      assert(!os.exists(extensionFile))

  /** Read the file `--append-system-prompt` points at, failing on a missing
    * flag rather than reading whatever argument happens to sit at index 0.
    */
  private def readSystemPrompt(args: List[String]): String =
    val idx = args.indexOf("--append-system-prompt")
    require(idx >= 0, s"no --append-system-prompt in $args")
    os.read(os.Path(args(idx + 1)))

  /** The header line pi's `--continue` requires — what
    * [[PiSessionStore.resumable]] probes for.
    */
  private def transcript(cwd: os.Path): String =
    s"""{"type":"session","id":"${SessionId.value(sid)}","cwd":"$cwd"}""" + "\n"

  /** Run one successful turn (which commits the session) and return the backend
    * plus the session dir Pi was pointed at. The stub never spawns Pi, so that
    * dir does not exist until a test creates it — which is what lets the probe
    * cases below be set up by hand.
    */
  private def committedSession(): (PiBackend, os.Path) =
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    val backend = backendWith(runner)
    val _ = backend.runAutonomous("one", sid, AgentConfig())
    val args = runner.spawnCalls.head.args
    (backend, os.Path(args(args.indexOf("--session-dir") + 1)))

  test("probing a rehydrated session does not create .orca"):
    // The read path (a wire id replayed from the progress log, then probed)
    // must stay effect-free — only spawning pi creates the session dirs.
    val workDir = TempDirs.dir()
    val backend = new PiBackend(new SpawnStubCliRunner(Nil), workDir = workDir)
    backend.sessions.register(sid, sid.onWire)
    val _ = backend.sessions.willContinue(sid)
    assert(!os.exists(workDir / ".orca"))

  test(
    "willContinue is true when the committed session dir holds a transcript"
  ):
    val (backend, dir) = committedSession()
    os.write(
      dir / "session.jsonl",
      transcript(backend.workDir),
      createFolders = true
    )
    assert(backend.sessions.willContinue(sid))

  test("willContinue is false when the committed session dir is gone"):
    val (backend, _) = committedSession()
    assert(!backend.sessions.willContinue(sid))

  test("persistableWireId is the claimed client id once the session commits"):
    val (backend, _) = committedSession()
    assertEquals(backend.sessions.persistableWireId(sid), Some(sid.onWire))

  // ── retention prune ────────────────────────────────────────────────────────

  /** Fixed "now" for every prune test, so ages are exact rather than raced
    * against the wall clock.
    */
  private val now = Instant.parse("2026-07-28T12:00:00Z")

  /** The prune's exact boundary: a dir stamped here is kept, one stamped a
    * millisecond earlier is deleted.
    */
  private val cutoff = now.minus(PiSessionStore.Retention)

  /** A leftover session dir stamped `at`, holding one transcript stamped the
    * same unless `withTranscript` is false (an empty dir — pi crashed before
    * seeding one) — the shapes the prune inspects.
    */
  private def sessionDirStamped(
      workDir: os.Path,
      name: String,
      at: Instant,
      withTranscript: Boolean = true
  ): os.Path =
    val dir = OrcaDir.piSessionsPath(workDir) / name
    os.makeDir.all(dir)
    if withTranscript then
      os.write(dir / "session.jsonl", transcript(workDir))
      os.mtime.set(dir / "session.jsonl", at.toEpochMilli): Unit
    os.mtime.set(dir, at.toEpochMilli): Unit
    dir

  /** A backend on `workDir` reading the fixed [[now]], built the runtime's way
    * — so it prunes before it is handed back.
    */
  private def backendAt(workDir: os.Path): PiBackend =
    PiBackend.create(new SpawnStubCliRunner(Nil), workDir, () => now)

  test("a session dir untouched since before the cutoff is pruned"):
    val workDir = TempDirs.dir()
    val stale = sessionDirStamped(workDir, "stale", cutoff.minusMillis(1))
    val _ = backendAt(workDir)
    assert(!os.exists(stale))

  test("a session dir touched at the cutoff is kept"):
    val workDir = TempDirs.dir()
    val recent = sessionDirStamped(workDir, "recent", cutoff)
    val _ = backendAt(workDir)
    assert(os.exists(recent))

  test("an empty session dir is judged by its own mtime"):
    // With nothing inside to date it, only the dir's own stamp decides.
    val workDir = TempDirs.dir()
    val abandoned =
      sessionDirStamped(
        workDir,
        "abandoned",
        cutoff.minusMillis(1),
        withTranscript = false
      )
    val _ = backendAt(workDir)
    assert(!os.exists(abandoned))

  test("an old dir whose transcript was appended after the cutoff is kept"):
    // Appending to an existing file leaves the directory's own mtime at
    // creation time, so freshness must come from the files too.
    val workDir = TempDirs.dir()
    val used = sessionDirStamped(workDir, "long-running", cutoff.minusMillis(1))
    os.mtime.set(used / "session.jsonl", now.toEpochMilli): Unit
    val _ = backendAt(workDir)
    assert(os.exists(used))

  test("create on a fresh workDir creates nothing under .orca"):
    val workDir = TempDirs.dir()
    val _ = backendAt(workDir)
    assert(!os.exists(workDir / ".orca"))

  test("the probe reports absent for a session dir already past the cutoff"):
    // Planted after construction, so the prune can't be what removes it: the
    // probe's own cutoff is what must report absence — the same answer another
    // process's prune would force a moment later.
    val workDir = TempDirs.dir()
    val backend = backendAt(workDir)
    val _ =
      sessionDirStamped(workDir, SessionId.value(sid), cutoff.minusMillis(1))
    backend.sessions.register(sid, sid.onWire)
    assert(!backend.sessions.willContinue(sid))

  test("a stale dir the prune trips over is skipped, sparing the rest"):
    val stale = cutoff.minusMillis(1)
    val workDir = TempDirs.dir()
    val broken = sessionDirStamped(workDir, "a-broken", stale)
    // A dangling link: reading its mtime follows the link and throws, which is
    // the failure the per-candidate guard has to absorb. Re-stamped afterwards,
    // since adding an entry bumps the directory's own mtime.
    os.symlink(broken / "broken.jsonl", broken / "nowhere")
    os.mtime.set(broken, stale.toEpochMilli): Unit
    // Siblings that all sort after it, so a missing per-candidate guard is
    // caught rather than raced: the prune walks in sorted order, and one
    // escaping throw would abort the scan before any of them.
    val siblings = List("b", "c", "d").map(sessionDirStamped(workDir, _, stale))

    val _ = backendAt(workDir)

    assert(os.exists(broken))
    siblings.foreach(dir => assert(!os.exists(dir), dir))

  test("the prune bails out on a symlinked pi-sessions root"):
    // A committed symlink at the root would otherwise let the recursive delete
    // reach outside the working tree.
    val workDir = TempDirs.dir()
    val target = TempDirs.dir()
    val victim = target / "victim"
    os.makeDir.all(victim)
    os.mtime.set(victim, cutoff.minusMillis(1).toEpochMilli): Unit
    val _ = OrcaDir.ensureCache(workDir)
    os.symlink(OrcaDir.piSessionsPath(workDir), target)

    val _ = backendAt(workDir)

    assert(os.exists(victim))
