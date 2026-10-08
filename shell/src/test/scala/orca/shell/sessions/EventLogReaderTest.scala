package orca.shell.sessions

import orca.{AttemptId, OrcaDir, OrcaFlowException, RunKey, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.runlog.RunEvent
import orca.runner.manifest.{AttemptManifest, AttemptStatus}
import orca.shell.sessions.EventLogFixtures.{
  durable,
  ephemeral,
  manifest,
  writeEventLog
}
import orca.testkit.TempDirs

import java.time.Instant

class EventLogReaderTest extends munit.FunSuite:

  private val alwaysDead: AttemptManifest => Boolean = _ => false
  private val alwaysAlive: AttemptManifest => Boolean = _ => true

  private val key = RunKey.of("a prompt")

  private def runsDir(workDir: os.Path): os.Path =
    workDir / ".orca" / "cache" / "runs"

  /** A succeeded attempt with one session, so the listing offers it. */
  private def writeAttempt(
      workDir: os.Path,
      startedAt: String,
      status: AttemptStatus = AttemptStatus.Succeeded,
      runKey: RunKey = key
  ): Unit =
    writeEventLog(
      workDir,
      manifest(
        workDir = workDir.toString,
        startedAt = startedAt,
        status = status,
        sessions = List(durable(lastActiveAt = startedAt))
      ),
      runKey
    )

  private def appendLines(workDir: os.Path, lines: String*): Unit =
    os.write.append(
      OrcaDir.eventLogPath(workDir, key),
      lines.map(_ + "\n").mkString,
      createFolders = true
    )

  private val attemptId = AttemptId(Instant.parse("2026-07-18T10:00:00Z"), 111)

  private def started: RunEvent =
    RunEvent.AttemptStarted(
      attemptId.startedAt,
      attemptId,
      schema = RunEvent.Schema,
      orcaVersion = "0.0.test",
      flow = None,
      workDir = "/work",
      pid = attemptId.pid,
      trace = None
    )

  private def commit(
      conversationKey: String,
      at: String,
      backend: BackendTag = BackendTag.ClaudeCode,
      stage: Option[String] = None,
      minted: Option[SessionKey] = None
  ): RunEvent =
    RunEvent.SessionCommitted(
      Instant.parse(at),
      attemptId,
      backend = backend,
      wireId = Some(conversationKey),
      conversationKey = conversationKey,
      agent = "claude",
      role = None,
      minted = minted,
      stage = stage.map(StagePath.FlowBody.child(_, 0))
    )

  private def appendEvents(workDir: os.Path, events: RunEvent*): Unit =
    appendLines(workDir, events.map(RunEvent.encodeLine)*)

  test("an absent runs dir lists nothing and creates nothing"):
    val workDir = TempDirs.dir()
    assertEquals(
      EventLogReader.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )
    assert(!os.exists(workDir / ".orca"), "reading must not create .orca")

  test("an attempt reads back as the attempt its events record"):
    val workDir = TempDirs.dir()
    val written = manifest(
      workDir = workDir.toString,
      branch = Some("orca-fix"),
      sessions = List(durable(stage = Some("code")), ephemeral(agent = "r"))
    )
    writeEventLog(workDir, written)
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.map(_.manifest), List(written))
    assertEquals(
      attempts.map(_.id),
      List(AttemptId(written.startedAt, written.pid))
    )

  test("attempts are ordered newest-first by startedAt"):
    val workDir = TempDirs.dir()
    List("2026-07-18T10:00:00Z", "2026-07-18T12:00:00Z", "2026-07-18T11:00:00Z")
      .foreach(writeAttempt(workDir, _))
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.manifest.startedAt.toString),
      List(
        "2026-07-18T12:00:00Z",
        "2026-07-18T11:00:00Z",
        "2026-07-18T10:00:00Z"
      )
    )

  test(
    "repeated commits are one session, at its first position, with the latest stage and time"
  ):
    val workDir = TempDirs.dir()
    val coder = SessionKey("coder", StagePath.FlowBody)
    appendEvents(
      workDir,
      started,
      commit(
        "a",
        "2026-07-18T10:01:00Z",
        stage = Some("plan"),
        minted = Some(coder)
      ),
      commit("b", "2026-07-18T10:02:00Z"),
      commit("a", "2026-07-18T10:03:00Z", stage = Some("code"))
    )
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    val sessions = attempts.flatMap(_.manifest.sessions)
    assertEquals(sessions.map(_.wireId), List(Some("a"), Some("b")))
    assertEquals(sessions.head.stage, Some("code"))
    assertEquals(
      sessions.head.lastActiveAt,
      Instant.parse("2026-07-18T10:03:00Z")
    )
    assertEquals(sessions.head.minted, Some(coder))

  test("the same conversation key on another backend is another session"):
    val workDir = TempDirs.dir()
    appendEvents(
      workDir,
      started,
      commit("a", "2026-07-18T10:01:00Z"),
      commit("a", "2026-07-18T10:02:00Z", backend = BackendTag.Codex)
    )
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(
      attempts.flatMap(_.manifest.sessions).map(_.backend),
      List(BackendTag.ClaudeCode, BackendTag.Codex)
    )

  test("attempts sharing one run's event log are listed separately"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z")
    writeAttempt(workDir, "2026-07-18T11:00:00Z")
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.manifest.sessions.size), List(1, 1))

  test("every run directory is read, and other entries under runs are ignored"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z")
    writeAttempt(workDir, "2026-07-18T11:00:00Z", runKey = RunKey.of("other"))
    os.write(runsDir(workDir) / "x.sessions.json", "{}")
    os.write(
      runsDir(workDir) / "traces-only" / "1-1.trace.log",
      "",
      createFolders = true
    )
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 2)

  test("an attempt that committed no session is dropped"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started)
    assertEquals(
      EventLogReader.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )

  test("an unfinished attempt with a dead pid is crashed"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Running)
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Crashed))

  test("an unfinished attempt with a live pid is running"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Running)
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysAlive)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Running))

  test("a failed attempt with a dead pid is not crashed"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Failed)
    val AttemptListing(attempts, _) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Failed))

  test("a torn last line is skipped without a warning"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started, commit("a", "2026-07-18T10:01:00Z"))
    appendLines(workDir, """{"type":"SessionCommitted","at":"2026-07""")
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.flatMap(_.manifest.sessions).size, 1)

  test("an event of an unknown type is skipped without a warning"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started, commit("a", "2026-07-18T10:01:00Z"))
    appendLines(
      workDir,
      s"""{"type":"SomethingNew","at":"2026-07-18T10:02:00Z","attempt":"${attemptId.value}"}"""
    )
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 1)

  test(
    "an attempt with no AttemptStarted is skipped with a warning naming the file"
  ):
    val workDir = TempDirs.dir()
    appendEvents(workDir, commit("a", "2026-07-18T10:01:00Z"))
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, alwaysDead)
    assertEquals(attempts, Nil)
    assertEquals(warnings.size, 1)
    assert(
      warnings.head.contains(OrcaDir.eventLogPath(workDir, key).toString),
      warnings.head
    )

  test("an unreadable event log is skipped with a warning naming the file"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z")
    val log = OrcaDir.eventLogPath(workDir, key)
    os.perms.set(log, "---------")
    assume(
      scala.util.Try(os.read(log)).isFailure,
      "needs a user that file permissions apply to"
    )
    try
      val AttemptListing(attempts, warnings) =
        EventLogReader.list(workDir, Nil, alwaysDead)
      assertEquals(attempts, Nil)
      assertEquals(warnings.size, 1)
      assert(warnings.head.contains(log.toString), warnings.head)
    finally os.perms.set(log, "rw-r--r--")

  test("warnings from the own dir and from other worktrees are all reported"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    appendEvents(checkout, commit("a", "2026-07-18T10:01:00Z"))
    appendEvents(worktree, commit("a", "2026-07-18T10:01:00Z"))
    val AttemptListing(_, warnings) =
      EventLogReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(warnings.size, 2)
    List(checkout, worktree).foreach: dir =>
      val log = OrcaDir.eventLogPath(dir, key).toString
      assert(warnings.exists(_.contains(log)), warnings.toString)

  test("a symlinked .orca/cache/runs aborts the listing"):
    val workDir = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(workDir / ".orca" / "cache")
    os.symlink(runsDir(workDir), outside)
    val ex = intercept[OrcaFlowException](
      EventLogReader.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("a symlinked run directory aborts the listing"):
    val workDir = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-run"
    os.write(outside / OrcaDir.EventLogName, "", createFolders = true)
    os.makeDir.all(runsDir(workDir))
    os.symlink(runsDir(workDir) / key.value, outside)
    val ex = intercept[OrcaFlowException](
      EventLogReader.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("attempts across worktrees are ordered newest-first across all of them"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeAttempt(checkout, "2026-07-18T10:00:00Z")
    writeAttempt(worktree, "2026-07-18T12:00:00Z")
    writeAttempt(checkout, "2026-07-18T11:00:00Z")
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.manifest.startedAt.toString),
      List(
        "2026-07-18T12:00:00Z",
        "2026-07-18T11:00:00Z",
        "2026-07-18T10:00:00Z"
      )
    )

  test("an unreadable worktree is one warning, not a lost listing"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeAttempt(checkout, "2026-07-18T10:00:00Z")
    // A tree left behind by a run under another uid, or one being removed in
    // another terminal: it must not take the shell's own attempts down with
    // it.
    os.makeDir.all(runsDir(worktree))
    os.perms.set(runsDir(worktree), "---------")
    assume(
      scala.util.Try(os.list(runsDir(worktree))).isFailure,
      "needs a user that file permissions apply to"
    )
    try
      val AttemptListing(attempts, warnings) =
        EventLogReader.list(checkout, List(worktree), alwaysDead)
      assertEquals(attempts.size, 1)
      assertEquals(warnings.size, 1)
      assert(warnings.head.contains(worktree.toString), warnings.head)
    finally os.perms.set(runsDir(worktree), "rwxr-xr-x")

  test("a symlinked .orca in another worktree warns, it does not abort"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeAttempt(checkout, "2026-07-18T10:00:00Z")
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(worktree / ".orca" / "cache")
    os.symlink(runsDir(worktree), outside)
    // The hard abort stays for the caller's OWN directory (the case above);
    // refusing to read someone else's tree is the whole remedy there.
    val AttemptListing(attempts, warnings) =
      EventLogReader.list(checkout, List(worktree), alwaysDead)
    assertEquals(attempts.size, 1)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("symlink"), warnings.head)
