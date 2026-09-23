package orca.runner.manifest

import orca.{AttemptId, OrcaDir, StagePath}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.agents.{BackendTag, SessionKey}
import orca.events.OrcaEvent
import orca.testkit.TempDirs
import ox.channels.BufferCapacity
import ox.supervised

import java.time.Instant

/** Unit tests for [[AttemptManifestWriter]]: upsert semantics, stage stamping,
  * wireId-less non-resumability, pruning, the wire shape, and thread-safety.
  * Uses plain temp workDirs (no git needed — the writer only touches
  * `.orca/cache/attempts/`).
  *
  * Single-scenario tests drive [[AttemptManifestWriterState]] directly and
  * synchronously; the concurrency test goes through
  * [[AttemptManifestWriter.start]] so it exercises the Ox actor's mailbox
  * serialisation (mirrors the TerminalOutputState / TerminalOutputActor test
  * split).
  */
class AttemptManifestWriterTest extends munit.FunSuite:

  /** The key the durable-session cases commit under and expect back. */
  private val coderKey =
    SessionKey(name = "coder", stage = StagePath.FlowBody.child("Task 2", 0))

  private def fixedClock(instants: Instant*): () => Instant =
    val it = instants.iterator
    () => if it.hasNext then it.next() else instants.last

  private def newWriter(
      workDir: os.Path,
      clock: () => Instant,
      flowName: Option[String] = None
  ): AttemptManifestWriterState =
    new AttemptManifestWriterState(
      workDir,
      "0.0.test",
      flowName,
      AttemptId(clock(), pid = 1),
      clock
    )

  private def manifestFiles(workDir: os.Path): List[os.Path] =
    os.list(OrcaDir.ensureAttempts(workDir)).filter(OrcaDir.isManifest).toList

  private def readManifest(path: os.Path): AttemptManifest =
    readFromString[AttemptManifest](os.read(path))(using AttemptManifest.codec)

  private def soleManifest(workDir: os.Path): AttemptManifest =
    val files = manifestFiles(workDir)
    assertEquals(
      files.size,
      1,
      s"expected exactly one manifest file, got: $files"
    )
    readManifest(files.head)

  /** A manifest with one session, as a pruning test seeds an earlier attempt
    * that the shell can still offer.
    */
  private def manifestWithSession(startedAt: String): String =
    s"""{"orcaVersion":"0.0.test","flow":null,"workDir":"/work","pid":1,
       |"startedAt":"$startedAt","finishedAt":null,"status":"Succeeded",
       |"sessions":[{"harness":"ClaudeCode","wireId":"w","agent":"claude",
       |"role":null,"stage":null,"lastActiveAt":"$startedAt"}]}""".stripMargin

  /** A valid manifest recording no session, as a pruning test seeds an attempt
    * the shell has nothing to offer from.
    */
  private def manifestWithoutSessions(startedAt: String): String =
    s"""{"orcaVersion":"0.0.test","flow":null,"workDir":"/work","pid":1,
       |"startedAt":"$startedAt","finishedAt":null,"status":"Succeeded",
       |"sessions":[]}""".stripMargin

  test("the manifest exists with status running as soon as the writer does"):
    val workDir = TempDirs.dir()
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    val manifest = soleManifest(workDir)
    assertEquals(manifest.status, AttemptStatus.Running)
    assertEquals(manifest.sessions, Nil)
    assertEquals(manifest.startedAt, Instant.parse("2026-07-18T10:00:00Z"))

  /** The wire shape the shell reads: every field by its persisted key, so a
    * renamed field — optional ones included — fails here even though the round
    * trip through one codec would not notice.
    */
  test("a finished manifest with one durable session has this exact JSON"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"), // attempt id: startedAt
        Instant.parse("2026-07-18T10:01:00Z"), // SessionCommitted
        Instant.parse("2026-07-18T10:05:00Z") // finish
      ),
      flowName = Some("review-pr.sc")
    )
    writer.onEvent(OrcaEvent.BranchBound("feat/x"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = Some(coderKey),
        agent = "claude",
        role = Some("coder")
      )
    )
    writer.finish(AttemptOutcome.Succeeded)
    val startedAt = Instant.parse("2026-07-18T10:00:00Z")
    assertEquals(
      os.read(OrcaDir.manifestPath(workDir, AttemptId(startedAt, 1))),
      s"""{"orcaVersion":"0.0.test","flow":"review-pr.sc","workDir":"$workDir",""" +
        """"branch":"feat/x","pid":1,"startedAt":"2026-07-18T10:00:00Z",""" +
        """"finishedAt":"2026-07-18T10:05:00Z","status":"Succeeded",""" +
        """"sessions":[{"harness":"ClaudeCode","wireId":"wire-1","agent":"claude",""" +
        """"role":"coder","stage":"code","minted":{"name":"coder","stage":"Task 2#0"},""" +
        """"lastActiveAt":"2026-07-18T10:01:00Z"}]}"""
    )

  test("a manifest without a branch key decodes with no branch"):
    val manifest = readFromString[AttemptManifest](
      manifestWithSession("2026-07-18T09:00:00Z")
    )(using AttemptManifest.codec)
    assertEquals(manifest.branch, None)

  test("upsert: same session re-firing updates stage/lastActiveAt"):
    val workDir = TempDirs.dir()
    // Only the attempt id (startedAt) and each SessionCommitted call clock() —
    // stage events don't, so 3 instants cover the id + 2 commits.
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"), // attempt id: startedAt
        Instant.parse("2026-07-18T10:01:00Z"), // SessionCommitted #1
        Instant.parse("2026-07-18T10:04:00Z") // SessionCommitted #2 (refire)
      )
    )
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = None,
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = None,
        agent = "claude",
        role = None
      )
    )
    val manifest = soleManifest(workDir)
    assertEquals(
      manifest.sessions.size,
      1,
      "same dedup key must upsert, not append"
    )
    val session = manifest.sessions.head
    assertEquals(session.lastActiveAt, Instant.parse("2026-07-18T10:04:00Z"))
    assertEquals(session.stage, Some("code"))

  test("nested stages stamp the top of the stack"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("outer"))
    writer.onEvent(OrcaEvent.StageStarted("inner"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = None,
        agent = "claude",
        role = None
      )
    )
    assertEquals(soleManifest(workDir).sessions.head.stage, Some("inner"))
    writer.onEvent(OrcaEvent.StageCompleted("inner"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.Codex,
        clientId = "client-2",
        wireId = Some("wire-2"),
        sessionKey = None,
        agent = "codex",
        role = None
      )
    )
    val manifest = soleManifest(workDir)
    val outerSession = manifest.sessions.find(_.harness == BackendTag.Codex).get
    assertEquals(outerSession.stage, Some("outer"))

  test("an event without a wireId is recorded with none"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.Opencode,
        clientId = "client-1",
        wireId = None,
        sessionKey = None,
        agent = "some",
        role = None
      )
    )
    assertEquals(soleManifest(workDir).sessions.head.wireId, None)

  test("a keyed commit records its key as minted; a keyless one records none"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "durable-client",
        wireId = Some("w1"),
        sessionKey = Some(coderKey),
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "ephemeral-client",
        wireId = Some("w2"),
        sessionKey = None,
        agent = "claude",
        role = None
      )
    )
    val sessions = soleManifest(workDir).sessions
    val durable = sessions.find(_.wireId.contains("w1")).get
    val ephemeral = sessions.find(_.wireId.contains("w2")).get
    assertEquals(durable.minted, Some(coderKey))
    assertEquals(ephemeral.minted, None)

  test("a later keyless commit on the same session keeps the durable key"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = Some(coderKey),
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = BackendTag.ClaudeCode,
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionKey = None,
        agent = "claude",
        role = None
      )
    )
    val sessions = soleManifest(workDir).sessions
    assertEquals(sessions.size, 1, "same dedup key must upsert, not append")
    assertEquals(sessions.head.minted, Some(coderKey))

  test("finish finalizes status and finishedAt"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"),
        Instant.parse("2026-07-18T10:05:00Z")
      )
    )
    writer.finish(AttemptOutcome.Succeeded)
    val manifest = soleManifest(workDir)
    assertEquals(manifest.status, AttemptStatus.Succeeded)
    assertEquals(
      manifest.finishedAt,
      Some(Instant.parse("2026-07-18T10:05:00Z"))
    )

  test("finish(Failed) records status failed on disk"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"),
        Instant.parse("2026-07-18T10:05:00Z")
      )
    )
    writer.finish(AttemptOutcome.Failed)
    assertEquals(soleManifest(workDir).status, AttemptStatus.Failed)

  /** Pruning counts attempts, not files. An attempt owns several, so a file
    * count would shrink the budget, and could delete a manifest while leaving
    * its cost log behind forever.
    */
  test(
    "both files of a pruned attempt are deleted, and the pair counts as one"
  ):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    // Fixed-width epoch-ms-like names, all older than the writer's own clock
    // (year-2000-ish millis), so the new manifest sorts newest.
    for i <- 1 to 25 do
      os.write(attemptsDir / f"1000000000$i%03d-1.manifest.json", "{}")
      os.write(attemptsDir / f"1000000000$i%03d-1.cost.jsonl", "")
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    // 25 seeded attempts plus this one, kept down to 20 attempts — so 20
    // manifests, not the 10 a file count would leave.
    assertEquals(manifestFiles(workDir).size, 20)
    assert(
      !os.exists(attemptsDir / "1000000000001-1.manifest.json") &&
        !os.exists(attemptsDir / "1000000000001-1.cost.jsonl"),
      "the oldest attempt's two files must both be gone"
    )
    assert(
      os.exists(attemptsDir / "1000000000025-1.manifest.json") &&
        os.exists(attemptsDir / "1000000000025-1.cost.jsonl"),
      "the newest seeded attempt's two files must both survive"
    )

  test("a pruned attempt's trace log and its rolled part are deleted"):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    for i <- 1 to 25 do
      os.write(attemptsDir / f"1000000000$i%03d-1.manifest.json", "{}")
    os.write(attemptsDir / "1000000000001-1.trace.log", "")
    os.write(attemptsDir / "1000000000001-1.trace.1.log", "")
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    assert(
      !os.exists(attemptsDir / "1000000000001-1.trace.log") &&
        !os.exists(attemptsDir / "1000000000001-1.trace.1.log")
    )

  /** An attempt that spends tokens without committing a session is the norm,
    * not the exception: every fresh run names its branch with a cheap agent
    * call before its first stage. Ranked by attempt id alone, twenty of them
    * would empty the shell's "continue a session" list.
    */
  test("session-less attempts never evict one whose manifest has a session"):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    for i <- 1 to 20 do
      os.write(
        attemptsDir / f"1000000000$i%03d-1.manifest.json",
        manifestWithSession("2026-07-18T09:00:00Z")
      )
    for i <- 21 to 40 do
      os.write(
        attemptsDir / f"1000000000$i%03d-1.manifest.json",
        manifestWithoutSessions("2026-07-18T09:30:00Z")
      )
      os.write(attemptsDir / f"1000000000$i%03d-1.cost.jsonl", "")
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    assert(
      (1 to 20).forall(i =>
        os.exists(attemptsDir / f"1000000000$i%03d-1.manifest.json")
      ),
      "every attempt with a session must survive"
    )

  /** Keeping every session-less attempt newer than the oldest kept attempt with
    * a session would grow without bound in a workdir that stops committing
    * sessions, so they are ranked among themselves too.
    */
  test("session-less attempts are bounded even where none with a session goes"):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    for i <- 1 to 20 do
      os.write(
        attemptsDir / f"1000000000$i%03d-1.manifest.json",
        manifestWithSession("2026-07-18T09:00:00Z")
      )
    for i <- 21 to 60 do
      os.write(
        attemptsDir / f"1000000000$i%03d-1.manifest.json",
        manifestWithoutSessions("2026-07-18T09:30:00Z")
      )
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    // 20 with a session, plus the newest 20 of any kind (this attempt and 19
    // session-less ones).
    assertEquals(manifestFiles(workDir).size, 40)

  /** Only the newest 20 of any kind survive here, so the corrupt file stays
    * exactly when it is counted as continuable — which it must not be.
    */
  test("a manifest that does not load counts as session-less when pruning"):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    os.write(attemptsDir / "1000000000001-1.manifest.json", "not json {{{")
    for i <- 2 to 21 do
      os.write(
        attemptsDir / f"1000000000$i%03d-1.manifest.json",
        manifestWithoutSessions("2026-07-18T09:30:00Z")
      )
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    assert(
      !os.exists(attemptsDir / "1000000000001-1.manifest.json"),
      "the unreadable manifest must not be kept as one with sessions"
    )

  test("an in-flight temp file survives pruning"):
    val workDir = TempDirs.dir()
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    os.write(attemptsDir / ".1000000000001-1.manifest.json.42.tmp", "{")
    for i <- 1 to 25 do
      os.write(attemptsDir / f"1000000000$i%03d-1.manifest.json", "{}")
    val _ =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    assert(os.exists(attemptsDir / ".1000000000001-1.manifest.json.42.tmp"))

  test(
    "concurrent onEvent calls from two threads don't corrupt the final file"
  ):
    val workDir = TempDirs.dir()
    supervised:
      given BufferCapacity = BufferCapacity(256)
      val writer = AttemptManifestWriter.start(
        workDir,
        "0.0.test",
        None,
        AttemptId(Instant.now(), pid = 1),
        () => Instant.now()
      )
      val threads = (0 until 2).map: t =>
        new Thread(() =>
          for i <- 0 until 50 do
            writer.onEvent(OrcaEvent.StageStarted(s"stage-$t-$i"))
            writer.onEvent(
              OrcaEvent.SessionCommitted(
                harness = BackendTag.ClaudeCode,
                clientId = s"client-$t-$i",
                wireId = Some(s"wire-$t-$i"),
                sessionKey = None,
                agent = "claude",
                role = None
              )
            )
            writer.onEvent(OrcaEvent.StageCompleted(s"stage-$t-$i"))
        )
      threads.foreach(_.start())
      threads.foreach(_.join())
      // `finish` is an `ask`: enqueued after every thread's tells (each
      // `join()`ed, so all their sends returned) and processed last, so its
      // final write reflects all 100 sessions. The actor's mailbox — not a
      // lock — is what serialises the racing read-modify-writes; without it,
      // two threads racing `state = state.copy(...)` would drop entries and
      // the count would fall below 100.
      writer.finish(AttemptOutcome.Succeeded)
      val manifest = soleManifest(workDir)
      assertEquals(manifest.status, AttemptStatus.Succeeded)
      assertEquals(
        manifest.sessions.size,
        100,
        "every distinct session must be recorded"
      )

  test("constructor fields (flowName, workDir) flow through into the manifest"):
    val workDir = TempDirs.dir()
    val _ = newWriter(
      workDir,
      fixedClock(Instant.parse("2026-07-18T10:00:00Z")),
      flowName = Some("review-pr.sc")
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.flow, Some("review-pr.sc"))
    assertEquals(manifest.workDir, workDir.toString)

  test("a failed write is swallowed and does not stop the next one"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    // A plain file where the attempts directory belongs: every write into it
    // fails.
    val attemptsDir = OrcaDir.ensureAttempts(workDir)
    os.remove.all(attemptsDir)
    os.write(attemptsDir, "not a directory")
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = BackendTag.ClaudeCode,
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionKey = None,
          agent = "claude",
          role = None
        )
    )
    os.remove(attemptsDir): Unit
    os.makeDir.all(attemptsDir)
    writer.finish(AttemptOutcome.Succeeded)
    assertEquals(soleManifest(workDir).status, AttemptStatus.Succeeded)
