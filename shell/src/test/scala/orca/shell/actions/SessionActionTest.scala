package orca.shell.actions

import orca.StagePath
import orca.shell.sessions.{AttemptRecord, RecordedSession}
import orca.shell.sessions.EventLogFixtures.{attemptRecord, durable, selection}
import orca.testkit.TempDirs
import orca.backend.pi.PiSessionStore
import orca.backend.cursor.CursorSessionStore
import orca.subprocess.{CliResult, StubCliRunner}

class SessionActionTest extends munit.FunSuite:

  private def session(stage: Option[String] = None): RecordedSession =
    durable(
      sessionName = "newest",
      sessionStage = StagePath.FlowBody.child("Task: fix a bug", 0),
      stage = stage,
      lastActiveAt = "2026-07-18T09:45:00Z"
    )

  private def attemptOf(s: RecordedSession): AttemptRecord =
    attemptRecord(startedAt = "2026-07-18T09:00:00Z", sessions = List(s))

  // `continue <name>` picks the newest of the sessions sharing a name, so this
  // line is where the user sees which one it landed on.
  test("identityNotice: names the session, harness, stage, and workDir"):
    val s = session()
    assertEquals(
      SessionAction.identityNotice(
        selection(attemptOf(s), s),
        "claude"
      ),
      "resuming session 'newest' [claude], in /work"
    )

  test("identityNotice: includes the stage when the session has one"):
    val s = session(stage = Some("Task: fix a bug"))
    assertEquals(
      SessionAction.identityNotice(
        selection(attemptOf(s), s),
        "claude"
      ),
      "resuming session 'newest' [claude], stage 'Task: fix a bug', in /work"
    )

  private def piDir(workDir: os.Path, id: String): os.Path =
    PiSessionStore.dirFor(workDir, id).get

  test("piSessionDir resolves a session dir that still holds a transcript"):
    val workDir = TempDirs.dir()
    val dir = piDir(workDir, "a-session")
    os.write(
      dir / "session.jsonl",
      s"""{"type":"session","id":"a-session","cwd":"$workDir"}""" + "\n",
      createFolders = true
    )
    assertEquals(
      SessionAction.piSessionDir("a-session", workDir),
      Right(dir)
    )

  test(
    "piSessionDir reports a pruned or never-written session, naming the dir"
  ):
    val workDir = TempDirs.dir()
    val Left(reason) =
      SessionAction.piSessionDir("a-session", workDir): @unchecked
    assert(reason.contains(piDir(workDir, "a-session").toString), reason)

  test("piSessionDir reports an id that isn't a directory name separately"):
    val workDir = TempDirs.dir()
    val Left(reason) =
      SessionAction.piSessionDir("../../etc", workDir): @unchecked
    assert(reason.contains("not a directory name"), reason)

  test("validatedWorkDir accepts a path that's still a directory"):
    val dir = TempDirs.dir()
    assertEquals(SessionAction.validatedWorkDir(dir.toString), Right(dir))

  test(
    "validatedWorkDir rejects a relative/malformed path (os.Path's IllegalArgumentException)"
  ):
    assertEquals(
      SessionAction.validatedWorkDir("not-an-absolute-path"),
      Left(
        "the recorded working directory not-an-absolute-path no longer exists"
      )
    )

  test(
    "validatedWorkDir rejects a well-formed but deleted directory (os.proc's cwd IOException, guarded before it happens)"
  ):
    val dir = TempDirs.dir()
    os.remove.all(dir)
    assertEquals(
      SessionAction.validatedWorkDir(dir.toString),
      Left(s"the recorded working directory $dir no longer exists")
    )

  test("Cursor history check accepts the recorded checkout only"):
    val workDir = TempDirs.dir()
    val chats = TempDirs.dir()
    val id = "6f0f1234-5678-4abc-9def-000000000001"
    os.write(
      CursorSessionStore.database(workDir, id, chats).get,
      "{}\n",
      createFolders = true
    )
    val cli = new StubCliRunner(CliResult(0, "1\n", ""))
    assertEquals(
      SessionAction.cursorHistory(id, workDir, chats, cli),
      Right(())
    )
    val elsewhere = TempDirs.dir()
    val Left(reason) =
      SessionAction.cursorHistory(id, elsewhere, chats, cli): @unchecked
    assert(reason.getMessage.contains(elsewhere.toString))
    assert(reason.getMessage.contains("rerun Orca"))

  test(
    "Cursor interactive continuation refuses an unverified store with recovery instructions"
  ):
    val workDir = TempDirs.dir()
    val chats = TempDirs.dir()
    val id = "6f0f1234-5678-4abc-9def-000000000001"
    os.write(
      CursorSessionStore.database(workDir, id, chats).get,
      "database fixture",
      createFolders = true
    )
    val cli = new StubCliRunner(CliResult(0, "0\n", ""))
    val Left(reason) =
      SessionAction.cursorHistory(id, workDir, chats, cli): @unchecked
    assert(reason.getMessage.contains(workDir.toString))
    assert(reason.getMessage.contains("sqlite3 on PATH"))
    assert(reason.getMessage.contains("rerun Orca"))
    assert(reason.getMessage.contains("re-seed"))
