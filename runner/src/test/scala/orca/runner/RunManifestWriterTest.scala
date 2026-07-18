package orca.runner

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.OrcaDir
import orca.WorkspaceWrite
import orca.events.OrcaEvent
import orca.progress.{ProgressHeader, ProgressStore, SessionRecord}
import orca.testkit.TempDirs

import java.time.Instant

/** Unit tests for [[RunManifestWriter]]: upsert semantics, stage stamping,
  * pi-shaped non-resumability, pruning, atomic writes, and thread-safety.
  * Uses plain temp workDirs (no git needed — the writer only touches
  * `.orca/cache/runs/` and reads `.orca/progress-*.json`).
  */
class RunManifestWriterTest extends munit.FunSuite:

  private def fixedClock(instants: Instant*): () => Instant =
    val it = instants.iterator
    () => if it.hasNext then it.next() else instants.last

  private def newWriter(
      workDir: os.Path,
      clock: () => Instant,
      flowName: Option[String] = None
  ): RunManifestWriter =
    new RunManifestWriter(workDir, "0.0.test", flowName, clock)

  private def manifestFiles(workDir: os.Path): List[os.Path] =
    os.list(OrcaDir.cacheRunsPath(workDir)).filter(_.ext == "json").toList

  private def readManifest(path: os.Path): RunManifest =
    readFromString[RunManifest](os.read(path))(using RunManifest.codec)

  private def soleManifest(workDir: os.Path): RunManifest =
    val files = manifestFiles(workDir)
    assertEquals(files.size, 1, s"expected exactly one manifest file, got: $files")
    readManifest(files.head)

  test("manifest file exists with outcome running after the first session event"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.outcome, "running")
    assertEquals(manifest.manifestVersion, 1)
    assertEquals(manifest.sessions.map(_.harness), List("claude"))

  test("upsert: same session re-firing updates stage/lastActiveAt, preserves firstSeenAt"):
    val workDir = TempDirs.dir()
    // Only the constructor (startedAt) and each SessionCommitted call clock() —
    // stage events don't, so 3 instants cover ctor + 2 commits.
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"), // constructor: startedAt
        Instant.parse("2026-07-18T10:01:00Z"), // SessionCommitted #1
        Instant.parse("2026-07-18T10:04:00Z") // SessionCommitted #2 (refire)
      )
    )
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.sessions.size, 1, "same dedup key must upsert, not append")
    val session = manifest.sessions.head
    assertEquals(session.firstSeenAt, "2026-07-18T10:01:00Z")
    assertEquals(session.lastActiveAt, "2026-07-18T10:04:00Z")
    assertEquals(session.stage, Some("code"))

  test("nested stages stamp the top of the stack"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("outer"))
    writer.onEvent(OrcaEvent.StageStarted("inner"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    assertEquals(soleManifest(workDir).sessions.head.stage, Some("inner"))
    writer.onEvent(OrcaEvent.StageCompleted("inner"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("codex", "client-2", Some("wire-2"), "codex", None)
    )
    val manifest = soleManifest(workDir)
    val outerSession = manifest.sessions.find(_.harness == "codex").get
    assertEquals(outerSession.stage, Some("outer"))

  test("pi-shaped event (wireId None) is not resumable and carries a reason"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted("pi", "client-1", None, "pi", None)
    )
    val session = soleManifest(workDir).sessions.head
    assertEquals(session.wireId, None)
    assertEquals(session.resumable, false)
    assertEquals(session.reason, Some("pi sessions do not survive the run"))

  test("kind: durable when clientId joins a SessionRecord, oneShot otherwise"):
    val workDir = TempDirs.dir()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val store = ProgressStore.default(workDir, "join-prompt")
    store.writeHeader(
      ProgressHeader(startingBranch = "main", branch = "main", promptHash = "abc")
    )
    store.upsertSession(
      SessionRecord(name = "coder", occurrence = 0, id = "durable-client", seed = "s")
    )
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "durable-client", Some("w1"), "claude", None)
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "oneshot-client", Some("w2"), "claude", None)
    )
    val sessions = soleManifest(workDir).sessions
    val durable = sessions.find(_.wireId.contains("w1")).get
    val oneShot = sessions.find(_.wireId.contains("w2")).get
    assertEquals(durable.kind, "durable")
    assertEquals(durable.sessionName, Some("coder"))
    assertEquals(oneShot.kind, "oneShot")
    assertEquals(oneShot.sessionName, None)

  test("finish finalizes outcome and finishedAt"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"),
        Instant.parse("2026-07-18T10:05:00Z")
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    writer.finish("succeeded")
    val manifest = soleManifest(workDir)
    assertEquals(manifest.outcome, "succeeded")
    assertEquals(manifest.finishedAt, Some("2026-07-18T10:05:00Z"))

  test("25 pre-seeded run files are pruned to the newest 20 on first write"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    // Fixed-width epoch-ms-like names, all older than the writer's own clock
    // (year-2000-ish millis), so the new manifest sorts newest.
    for i <- 1 to 25 do os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    val files = manifestFiles(workDir)
    assertEquals(files.size, 20, s"expected exactly 20 files after pruning, got ${files.size}")
    // The newly written manifest (this run's own file) must have survived.
    assert(
      files.exists(_.last == s"${Instant.parse("2026-07-18T10:00:00Z").toEpochMilli}-${ProcessHandle.current().pid()}.json"),
      "the current run's own manifest must survive pruning"
    )
    // The oldest pre-seeded files must be gone; the newest pre-seeded ones are kept.
    assert(!files.exists(_.last == "1000000000001-1.json"), "oldest pre-seeded file must be pruned")
    assert(files.exists(_.last == "1000000000025-1.json"), "newest pre-seeded file must survive")

  test("atomic write leaves no temp files behind"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    writer.finish("succeeded")
    val tmpFiles = os.list(OrcaDir.cacheRunsPath(workDir)).filter(_.last.endsWith(".tmp"))
    assertEquals(tmpFiles.toList, Nil, s"no temp files must remain, got: $tmpFiles")

  test("concurrent onEvent calls from two threads don't corrupt the final file"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, () => Instant.now())
    val threads = (0 until 2).map: t =>
      new Thread(() =>
        for i <- 0 until 50 do
          writer.onEvent(OrcaEvent.StageStarted(s"stage-$t-$i"))
          writer.onEvent(
            OrcaEvent.SessionCommitted(
              "claude",
              s"client-$t-$i",
              Some(s"wire-$t-$i"),
              "claude",
              None
            )
          )
          writer.onEvent(OrcaEvent.StageCompleted(s"stage-$t-$i"))
      )
    threads.foreach(_.start())
    threads.foreach(_.join())
    writer.finish("succeeded")
    // The atomic temp+move write already guarantees no torn file, with or
    // without locking — what only `synchronized` guarantees is that no
    // update is LOST to an unguarded read-modify-write race. So the real
    // assertion is the count: without the lock around each event, two
    // threads racing `state = state.copy(...)` drop entries and this falls
    // below 100.
    val manifest = soleManifest(workDir)
    assertEquals(manifest.outcome, "succeeded")
    assertEquals(manifest.sessions.size, 100, "every distinct session must be recorded")

  test("session-less run: finish writes and creates nothing"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"),
        Instant.parse("2026-07-18T10:05:00Z")
      )
    )
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.finish("failed")
    assertEquals(manifestFiles(workDir), Nil, "no manifest for a run with no committed session")

  test("stage events before the first SessionCommitted are not lost"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.sessions.head.stage, Some("code"))

  test("constructor fields (flowName, workDir) flow through into the manifest"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(Instant.parse("2026-07-18T10:00:00Z")),
      flowName = Some("review-pr.sc")
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.flow, Some("review-pr.sc"))
    assertEquals(manifest.workDir, workDir.toString)
