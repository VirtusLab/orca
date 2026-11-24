package orca.backend.cursor

import orca.agents.{AgentConfig, BackendTag, SessionId, WireSessionId, ToolSet}
import orca.backend.{Dispatch, ResumeOrigin, SupervisedBackend}
import orca.subprocess.{
  FakePipedCliProcess,
  SpawnStubCliRunner,
  StubCliRunner,
  PipedCliProcess,
  CliRunner
}
import orca.testkit.TempDirs

class CursorBackendTest extends munit.FunSuite:
  private val wire = "6f0f1234-5678-4abc-9def-000000000001"
  private val replacement = "6f0f1234-5678-4abc-9def-000000000002"
  private def client: SessionId[BackendTag.Cursor.type] = SessionId.fresh

  private def successful(id: String): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      s"""{"type":"system","subtype":"init","session_id":"$id"}"""
    )
    p.enqueueStdout("""{"type":"result","subtype":"success","result":"OK"}""")
    p.closeStdout()
    p.closeStderr()
    p

  private def history(workDir: os.Path, chats: os.Path): Unit =
    val stream =
      getClass.getResourceAsStream("/orca/backend/cursor/populated.db")
    try
      os.write(
        CursorSessionStore.database(workDir, wire, chats).get,
        stream.readAllBytes(),
        createFolders = true
      )
    finally stream.close()

  private def withProbe(
      processes: List[FakePipedCliProcess],
      probe: CliRunner
  ): SpawnStubCliRunner =
    new SpawnStubCliRunner(processes):
      override def spawnPiped(
          args: Seq[String],
          env: Map[String, String],
          cwd: os.Path
      ): PipedCliProcess =
        if args.head == "sqlite3" then
          probe.spawnPiped(args, env = env, cwd = cwd)
        else super.spawnPiped(args, env = env, cwd = cwd)

  private def scalar(value: String): SpawnStubCliRunner =
    val process = new FakePipedCliProcess(initiallyAlive = false)
    process.enqueueStdout(value)
    process.closeStdout()
    process.closeStderr()
    new SpawnStubCliRunner(List(process))

  test("fresh call spawns without resume and uses the configured checkout"):
    val workDir = TempDirs.dir()
    val cli = new SpawnStubCliRunner(List(successful(wire)))
    SupervisedBackend.using(
      new CursorBackend(cli, workDir = workDir, chatsRoot = TempDirs.dir())
    ): backend =>
      val result = backend.runAutonomous(
        "seed",
        client,
        AgentConfig(tools = ToolSet.ReadOnly)
      )
      assertEquals(result.wireId, WireSessionId[BackendTag.Cursor.type](wire))
      assert(!cli.calls.head.contains("--resume"))
      assert(cli.calls.head.contains("--plan"))
      assertEquals(cli.spawnCalls.head.cwd, workDir)

  test(
    "same-attempt continuation resumes without reprobing absent local history"
  ):
    val cli = new SpawnStubCliRunner(List(successful(wire), successful(wire)))
    SupervisedBackend.using(
      new CursorBackend(
        cli,
        workDir = TempDirs.dir(),
        chatsRoot = TempDirs.dir()
      )
    ): backend =>
      val id = client
      val _ = backend.runAutonomous("seed", id, AgentConfig())
      assertEquals(
        backend.sessions.dispatchFor(id),
        Dispatch.Resume(
          WireSessionId[BackendTag.Cursor.type](wire),
          ResumeOrigin.ThisAttempt
        )
      )
      val _ = backend.runAutonomous("continue", id, AgentConfig())
      assert(cli.calls(1).containsSlice(List("--resume", wire)))

  test("restored history resumes in the original checkout"):
    val workDir = TempDirs.dir()
    val chats = TempDirs.dir()
    history(workDir = workDir, chats = chats)
    val probe = scalar("1")
    val cli = withProbe(List(successful(wire)), probe)
    SupervisedBackend.using(
      new CursorBackend(cli, workDir = workDir, chatsRoot = chats)
    ): backend =>
      val id = client
      backend.sessions.rehydrate(id, WireSessionId(wire))
      assertEquals(
        backend.sessions.dispatchFor(id),
        Dispatch.Resume(
          WireSessionId[BackendTag.Cursor.type](wire),
          ResumeOrigin.EarlierAttempt
        )
      )
      val _ = backend.runAutonomous("continue", id, AgentConfig())
      assert(cli.calls.head.containsSlice(List("--resume", wire)))
      assertEquals(probe.calls.size, 1)
      assertEquals(probe.spawnCalls.head.cwd, workDir)

  test("stale persisted id re-seeds and commits the newly minted conversation"):
    val cli = new SpawnStubCliRunner(
      List(successful(replacement), successful(replacement))
    )
    SupervisedBackend.using(
      new CursorBackend(
        cli,
        workDir = TempDirs.dir(),
        chatsRoot = TempDirs.dir()
      )
    ): backend =>
      val id = client
      backend.sessions.rehydrate(id, WireSessionId(wire))
      assertEquals(backend.sessions.dispatchFor(id), Dispatch.Fresh(None))
      val _ = backend.runAutonomous("replayed seed", id, AgentConfig())
      val _ = backend.runAutonomous("continue", id, AgentConfig())
      assert(!cli.calls.head.contains("--resume"))
      assert(cli.calls.head.last.endsWith("User request:\nreplayed seed"))
      assert(cli.calls(1).containsSlice(List("--resume", replacement)))

  test("history from another worktree never resumes the persisted id"):
    val chats = TempDirs.dir()
    history(workDir = TempDirs.dir(), chats = chats)
    val cli = new SpawnStubCliRunner(List(successful(replacement)))
    SupervisedBackend.using(
      new CursorBackend(cli, workDir = TempDirs.dir(), chatsRoot = chats)
    ): backend =>
      val id = client
      backend.sessions.rehydrate(id, WireSessionId(wire))
      val _ =
        backend.runAutonomous("seed for this checkout", id, AgentConfig())
      assert(!cli.calls.head.contains("--resume"))

  test(
    "unverified persisted database re-seeds before continuing the new conversation"
  ):
    val workDir = TempDirs.dir()
    val chats = TempDirs.dir()
    history(workDir = workDir, chats = chats)
    val probe = scalar("0")
    val cli =
      withProbe(List(successful(replacement), successful(replacement)), probe)
    SupervisedBackend.using(
      new CursorBackend(cli, workDir = workDir, chatsRoot = chats)
    ): backend =>
      val id = client
      backend.sessions.rehydrate(id, WireSessionId(wire))
      val _ = backend.runAutonomous("replayed seed", id, AgentConfig())
      val _ = backend.runAutonomous("continue", id, AgentConfig())
      assert(!cli.calls.head.contains("--resume"))
      assert(cli.calls.head.last.endsWith("User request:\nreplayed seed"))
      assert(cli.calls(1).containsSlice(List("--resume", replacement)))
      assertEquals(probe.calls.size, 1)

  test("restored database loader failure re-seeds without resuming the old id"):
    val workDir = TempDirs.dir()
    val chats = TempDirs.dir()
    history(workDir = workDir, chats = chats)
    val probe = new StubCliRunner():
      override def spawnPiped(
          args: Seq[String],
          env: Map[String, String],
          cwd: os.Path
      ): PipedCliProcess =
        throw new java.io.IOException("sqlite3 unavailable")
    val cli = withProbe(List(successful(replacement)), probe)
    SupervisedBackend.using(
      new CursorBackend(cli, workDir = workDir, chatsRoot = chats)
    ): backend =>
      val id = client
      backend.sessions.rehydrate(id, WireSessionId(wire))
      val _ = backend.runAutonomous("replayed seed", id, AgentConfig())
      assert(!cli.calls.head.contains("--resume"))
      assert(cli.calls.head.last.endsWith("User request:\nreplayed seed"))
