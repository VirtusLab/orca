package orca.shell.sessions

import orca.{AttemptId, OrcaDir, OrcaFlowException, RunKey, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.runlog.RunEvent
import orca.shell.sessions.EventLogFixtures.{
  attemptRecord,
  durable,
  ephemeral,
  writeEventLog
}
import orca.testkit.TempDirs
import ox.discard

import java.time.Instant

class ContinuableAttemptsTest extends munit.FunSuite:

  private val alwaysDead: AttemptRecord => Boolean = _ => false
  private val alwaysAlive: AttemptRecord => Boolean = _ => true

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
      attemptRecord(
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
      ContinuableAttempts.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )
    assert(!os.exists(workDir / ".orca"), "reading must not create .orca")

  test("an attempt reads back as the attempt its events record"):
    val workDir = TempDirs.dir()
    val written = attemptRecord(
      workDir = workDir.toString,
      branch = Some("orca-fix"),
      sessions = List(durable(stage = Some("code")), ephemeral(agent = "r"))
    )
    writeEventLog(workDir, written)
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.map(_.record), List(written))
    assertEquals(
      attempts.map(_.id),
      List(AttemptId(written.startedAt, written.pid))
    )

  test("attempts are ordered newest-first by startedAt"):
    val workDir = TempDirs.dir()
    List("2026-07-18T10:00:00Z", "2026-07-18T12:00:00Z", "2026-07-18T11:00:00Z")
      .foreach(writeAttempt(workDir, _))
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.record.startedAt.toString),
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
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    val sessions = attempts.flatMap(_.record.sessions)
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
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(
      attempts.flatMap(_.record.sessions).map(_.backend),
      List(BackendTag.ClaudeCode, BackendTag.Codex)
    )

  test("attempts sharing one run's event log are listed separately"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z")
    writeAttempt(workDir, "2026-07-18T11:00:00Z")
    val AttemptListing(attempts, _) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.record.sessions.size), List(1, 1))

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
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 2)

  test("an attempt that committed no session is dropped"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started)
    assertEquals(
      ContinuableAttempts.list(workDir, Nil, alwaysDead),
      AttemptListing(Nil, Nil)
    )

  test("an unfinished attempt with a dead pid is crashed"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Running)
    val AttemptListing(attempts, _) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Crashed))

  test("an unfinished attempt with a live pid is running"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Running)
    val AttemptListing(attempts, _) =
      ContinuableAttempts.list(workDir, Nil, alwaysAlive)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Running))

  test("a failed attempt with a dead pid is not crashed"):
    val workDir = TempDirs.dir()
    writeAttempt(workDir, "2026-07-18T10:00:00Z", AttemptStatus.Failed)
    val AttemptListing(attempts, _) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Failed))

  test("a torn last line is skipped without a warning"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started, commit("a", "2026-07-18T10:01:00Z"))
    appendLines(workDir, """{"type":"SessionCommitted","at":"2026-07""")
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.flatMap(_.record.sessions).size, 1)

  test("an event of an unknown type is skipped without a warning"):
    val workDir = TempDirs.dir()
    appendEvents(workDir, started, commit("a", "2026-07-18T10:01:00Z"))
    appendLines(
      workDir,
      s"""{"type":"SomethingNew","at":"2026-07-18T10:02:00Z","attempt":"${attemptId.value}"}"""
    )
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 1)

  test(
    "an attempt with no AttemptStarted is skipped with a warning naming the file"
  ):
    val workDir = TempDirs.dir()
    appendEvents(workDir, commit("a", "2026-07-18T10:01:00Z"))
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
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
        ContinuableAttempts.list(workDir, Nil, alwaysDead)
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
      ContinuableAttempts.list(checkout, List(worktree), alwaysDead)
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
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("a symlinked run directory aborts the listing"):
    val workDir = TempDirs.dir()
    val outside = TempDirs.dir() / "outside-run"
    os.write(outside / OrcaDir.EventLogName, "", createFolders = true)
    os.makeDir.all(runsDir(workDir))
    os.symlink(runsDir(workDir) / key.value, outside)
    val ex = intercept[OrcaFlowException](
      ContinuableAttempts.list(workDir, Nil, alwaysDead)
    )
    assert(ex.getMessage.contains("symlink"), ex.getMessage)

  test("attempts across worktrees are ordered newest-first across all of them"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeAttempt(checkout, "2026-07-18T10:00:00Z")
    writeAttempt(worktree, "2026-07-18T12:00:00Z")
    writeAttempt(checkout, "2026-07-18T11:00:00Z")
    val AttemptListing(attempts, warnings) =
      ContinuableAttempts.list(checkout, List(worktree), alwaysDead)
    assertEquals(warnings, Nil)
    assertEquals(
      attempts.map(_.record.startedAt.toString),
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
        ContinuableAttempts.list(checkout, List(worktree), alwaysDead)
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
      ContinuableAttempts.list(checkout, List(worktree), alwaysDead)
    assertEquals(attempts.size, 1)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("symlink"), warnings.head)

  /** Writes an attempt started at `startedAt` with `sessions` sessions as run
    * `runKey`'s event log, last modified at `modifiedAt`.
    */
  private def writeDatedAttempt(
      workDir: os.Path,
      runKey: RunKey,
      startedAt: String,
      sessions: Int,
      modifiedAt: String
  ): Unit =
    writeEventLog(
      workDir,
      attemptRecord(
        workDir = workDir.toString,
        startedAt = startedAt,
        sessions = List.fill(sessions)(durable(lastActiveAt = startedAt))
      ),
      runKey
    )
    os.mtime
      .set(
        OrcaDir.eventLogPath(workDir, runKey),
        Instant.parse(modifiedAt).toEpochMilli
      )
      .discard

  test("the newest session count skips a newer attempt with no sessions"):
    val workDir = TempDirs.dir()
    writeDatedAttempt(
      workDir,
      RunKey.of("with sessions"),
      startedAt = "2026-07-18T10:00:00Z",
      sessions = 2,
      modifiedAt = "2026-07-18T10:30:00Z"
    )
    writeDatedAttempt(
      workDir,
      RunKey.of("no sessions"),
      startedAt = "2026-07-18T11:00:00Z",
      sessions = 0,
      modifiedAt = "2026-07-18T11:30:00Z"
    )
    assertEquals(ContinuableAttempts.newestSessionCount(workDir, Nil), Some(2))

  test("the newest session count does not read a log older than its attempt"):
    val workDir = TempDirs.dir()
    writeDatedAttempt(
      workDir,
      RunKey.of("kept"),
      startedAt = "2026-07-18T10:00:00Z",
      sessions = 1,
      modifiedAt = "2026-07-18T10:30:00Z"
    )
    // Inconsistent on purpose: a newer attempt in a log modified before the
    // kept attempt started. Its count shows up only if the log is read.
    writeDatedAttempt(
      workDir,
      RunKey.of("trap"),
      startedAt = "2026-07-18T12:00:00Z",
      sessions = 3,
      modifiedAt = "2026-07-18T09:00:00Z"
    )
    assertEquals(ContinuableAttempts.newestSessionCount(workDir, Nil), Some(1))

  test("the newest session count is None with no event logs"):
    assertEquals(
      ContinuableAttempts.newestSessionCount(TempDirs.dir(), Nil),
      None
    )

  test("the newest session count comes from the newest attempt in a log"):
    val workDir = TempDirs.dir()
    List(("2026-07-18T10:00:00Z", 1L, 2), ("2026-07-18T11:00:00Z", 2L, 1))
      .foreach: (startedAt, pid, sessions) =>
        writeEventLog(
          workDir,
          attemptRecord(
            workDir = workDir.toString,
            startedAt = startedAt,
            pid = pid,
            sessions = List.fill(sessions)(durable(lastActiveAt = startedAt))
          )
        )
    assertEquals(ContinuableAttempts.newestSessionCount(workDir, Nil), Some(1))

  test("the newest session count counts a repeated commit once"):
    val workDir = TempDirs.dir()
    appendEvents(
      workDir,
      started,
      commit("a", "2026-07-18T10:01:00Z"),
      commit("a", "2026-07-18T10:02:00Z"),
      commit("b", "2026-07-18T10:03:00Z")
    )
    assertEquals(ContinuableAttempts.newestSessionCount(workDir, Nil), Some(2))

  test("the newest session count spans other worktrees"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeDatedAttempt(
      checkout,
      key,
      startedAt = "2026-07-18T10:00:00Z",
      sessions = 1,
      modifiedAt = "2026-07-18T10:30:00Z"
    )
    writeDatedAttempt(
      worktree,
      key,
      startedAt = "2026-07-18T11:00:00Z",
      sessions = 2,
      modifiedAt = "2026-07-18T11:30:00Z"
    )
    assertEquals(
      ContinuableAttempts.newestSessionCount(checkout, List(worktree)),
      Some(2)
    )

  test("the newest session count skips a worktree with a symlinked .orca"):
    val checkout = TempDirs.dir()
    val worktree = TempDirs.dir()
    writeAttempt(checkout, "2026-07-18T10:00:00Z")
    val outside = TempDirs.dir() / "outside-runs"
    os.makeDir.all(outside)
    os.makeDir.all(worktree / ".orca" / "cache")
    os.symlink(runsDir(worktree), outside)
    assertEquals(
      ContinuableAttempts.newestSessionCount(checkout, List(worktree)),
      Some(1)
    )
