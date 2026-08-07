package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.OrcaDir
import orca.events.OrcaEvent
import orca.testkit.Usages.usage
import orca.testkit.TempDirs
import ox.channels.BufferCapacity
import ox.supervised

import java.time.Instant

/** Unit tests for [[RunManifestWriter]]: upsert semantics, stage stamping,
  * wireId-less non-resumability, pruning, atomic writes, and thread-safety.
  * Uses plain temp workDirs (no git needed — the writer only touches
  * `.orca/cache/runs/`).
  *
  * Single-scenario tests drive [[RunManifestWriterState]] directly and
  * synchronously; the concurrency test goes through [[RunManifestWriter.start]]
  * so it exercises the Ox actor's mailbox serialisation (mirrors the
  * TerminalOutputState / TerminalOutputActor test split).
  */
class RunManifestWriterTest extends munit.FunSuite:

  private def fixedClock(instants: Instant*): () => Instant =
    val it = instants.iterator
    () => if it.hasNext then it.next() else instants.last

  private def newWriter(
      workDir: os.Path,
      clock: () => Instant,
      flowName: Option[String] = None
  ): RunManifestWriterState =
    new RunManifestWriterState(workDir, "0.0.test", flowName, clock)

  private def manifestFiles(workDir: os.Path): List[os.Path] =
    os.list(OrcaDir.cacheRunsPath(workDir)).filter(_.ext == "json").toList

  private def costLogFiles(workDir: os.Path): List[os.Path] =
    os.list(OrcaDir.cacheRunsPath(workDir))
      .filter(_.last.endsWith("-cost.jsonl"))
      .toList

  private def readManifest(path: os.Path): RunManifest =
    readFromString[RunManifest](os.read(path))(using RunManifest.codec)

  private def soleManifest(workDir: os.Path): RunManifest =
    val files = manifestFiles(workDir)
    assertEquals(
      files.size,
      1,
      s"expected exactly one manifest file, got: $files"
    )
    readManifest(files.head)

  test(
    "manifest file exists with outcome running after the first session event"
  ):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.outcome, ManifestOutcome.Running)
    assertEquals(manifest.sessions.map(_.harness), List("claude"))

  test(
    "upsert: same session re-firing updates stage/lastActiveAt, preserves firstSeenAt"
  ):
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
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
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
    assertEquals(session.firstSeenAt, Instant.parse("2026-07-18T10:01:00Z"))
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
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    assertEquals(soleManifest(workDir).sessions.head.stage, Some("inner"))
    writer.onEvent(OrcaEvent.StageCompleted("inner"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "codex",
        clientId = "client-2",
        wireId = Some("wire-2"),
        sessionName = None,
        agent = "codex",
        role = None
      )
    )
    val manifest = soleManifest(workDir)
    val outerSession = manifest.sessions.find(_.harness == "codex").get
    assertEquals(outerSession.stage, Some("outer"))

  test("an event without a wireId is not resumable and carries a reason"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "someharness",
        clientId = "client-1",
        wireId = None,
        sessionName = None,
        agent = "some",
        role = None
      )
    )
    val session = soleManifest(workDir).sessions.head
    assertEquals(session.wireId, None)
    assertEquals(session.resumable, false)
    assertEquals(
      session.reason,
      Some("someharness sessions do not survive the run")
    )

  test("kind: durable when the event names the session, oneShot otherwise"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "durable-client",
        wireId = Some("w1"),
        sessionName = Some("coder"),
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "oneshot-client",
        wireId = Some("w2"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    val sessions = soleManifest(workDir).sessions
    val durable = sessions.find(_.wireId.contains("w1")).get
    val oneShot = sessions.find(_.wireId.contains("w2")).get
    assertEquals(durable.kind, ManifestSessionKind.Durable)
    assertEquals(durable.sessionName, Some("coder"))
    assertEquals(oneShot.kind, ManifestSessionKind.OneShot)
    assertEquals(oneShot.sessionName, None)

  test("a later unnamed commit on the same session keeps the durable name"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = Some("coder"),
        agent = "claude",
        role = None
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    val sessions = soleManifest(workDir).sessions
    assertEquals(sessions.size, 1, "same dedup key must upsert, not append")
    assertEquals(sessions.head.sessionName, Some("coder"))
    assertEquals(sessions.head.kind, ManifestSessionKind.Durable)

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
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    writer.finish(RunOutcome.Succeeded)
    val manifest = soleManifest(workDir)
    assertEquals(manifest.outcome, ManifestOutcome.Succeeded)
    assertEquals(
      manifest.finishedAt,
      Some(Instant.parse("2026-07-18T10:05:00Z"))
    )

  test("finish(Failed) records outcome failed on disk"):
    val workDir = TempDirs.dir()
    val writer = newWriter(
      workDir,
      fixedClock(
        Instant.parse("2026-07-18T10:00:00Z"),
        Instant.parse("2026-07-18T10:05:00Z")
      )
    )
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    writer.finish(RunOutcome.Failed)
    assertEquals(soleManifest(workDir).outcome, ManifestOutcome.Failed)

  test("25 pre-seeded run files are pruned to the newest 20 on first write"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    // Fixed-width epoch-ms-like names, all older than the writer's own clock
    // (year-2000-ish millis), so the new manifest sorts newest.
    for i <- 1 to 25 do os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    val files = manifestFiles(workDir)
    assertEquals(
      files.size,
      20,
      s"expected exactly 20 files after pruning, got ${files.size}"
    )
    // The newly written manifest (this run's own file) must have survived.
    assert(
      files.exists(
        _.last == s"${Instant.parse("2026-07-18T10:00:00Z").toEpochMilli}-${ProcessHandle.current().pid()}.json"
      ),
      "the current run's own manifest must survive pruning"
    )
    // The oldest pre-seeded files must be gone; the newest pre-seeded ones are kept.
    assert(
      !files.exists(_.last == "1000000000001-1.json"),
      "oldest pre-seeded file must be pruned"
    )
    assert(
      files.exists(_.last == "1000000000025-1.json"),
      "newest pre-seeded file must survive"
    )

  /** Pruning counts runs, not files. A run owns up to two, so a file count
    * would halve the budget, and could delete a manifest while leaving its cost
    * log behind forever.
    */
  test("both files of a pruned run are deleted, and the pair counts as one"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    for i <- 1 to 25 do
      os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
      os.write(runsDir / f"1000000000$i%03d-1-cost.jsonl", "")
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = "claude",
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionName = None,
          agent = "claude",
          role = None
        )
    )
    // 25 seeded runs plus this one, kept down to 20 runs — so 20 manifests,
    // not the 10 a file count would leave.
    assertEquals(manifestFiles(workDir).size, 20)
    assert(
      !os.exists(runsDir / "1000000000001-1.json") &&
        !os.exists(runsDir / "1000000000001-1-cost.jsonl"),
      "the oldest run's two files must both be gone"
    )
    assert(
      os.exists(runsDir / "1000000000025-1.json") &&
        os.exists(runsDir / "1000000000025-1-cost.jsonl"),
      "the newest seeded run's two files must both survive"
    )

  /** The trigger sits on the first write of EITHER file. Left on the manifest
    * path, a workdir whose runs keep spending tokens without committing a
    * session would grow without bound — and those runs are exactly the ones the
    * cost log records.
    */
  test("a run that only ever appends cost lines still prunes"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    for i <- 1 to 25 do os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1)))
    assert(
      !os.exists(runsDir / "1000000000001-1.json"),
      "the oldest of 25 seeded runs must be gone"
    )

  /** A run that spends tokens without committing a session is the norm, not the
    * exception: every fresh run names its branch with a cheap agent call before
    * its first stage. Ranked by run id alone, twenty of them would empty the
    * shell's "continue a session" list.
    */
  test("manifest-less runs never evict a run that owns a manifest"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    for i <- 1 to 20 do os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
    for i <- 21 to 40 do
      os.write(runsDir / f"1000000000$i%03d-1-cost.jsonl", "")
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1)))
    assertEquals(manifestFiles(workDir).size, 20)

  /** Keeping every manifest-less run newer than the oldest kept manifest would
    * grow without bound in a workdir that stops committing sessions, so they
    * are ranked among themselves too.
    */
  test("manifest-less runs are bounded even where no manifest is evicted"):
    val workDir = TempDirs.dir()
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    for i <- 1 to 20 do os.write(runsDir / f"1000000000$i%03d-1.json", "{}")
    for i <- 21 to 60 do
      os.write(runsDir / f"1000000000$i%03d-1-cost.jsonl", "")
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1)))
    assertEquals(costLogFiles(workDir).size, 20)

  test("atomic write leaves no temp files behind"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    writer.finish(RunOutcome.Succeeded)
    val tmpFiles =
      os.list(OrcaDir.cacheRunsPath(workDir)).filter(_.last.endsWith(".tmp"))
    assertEquals(
      tmpFiles.toList,
      Nil,
      s"no temp files must remain, got: $tmpFiles"
    )

  test(
    "concurrent onEvent calls from two threads don't corrupt the final file"
  ):
    val workDir = TempDirs.dir()
    supervised:
      given BufferCapacity = BufferCapacity(256)
      val writer = RunManifestWriter.start(
        workDir,
        "0.0.test",
        None,
        () => Instant.now()
      )
      val threads = (0 until 2).map: t =>
        new Thread(() =>
          for i <- 0 until 50 do
            writer.onEvent(OrcaEvent.StageStarted(s"stage-$t-$i"))
            writer.onEvent(
              OrcaEvent.SessionCommitted(
                harness = "claude",
                clientId = s"client-$t-$i",
                wireId = Some(s"wire-$t-$i"),
                sessionName = None,
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
      writer.finish(RunOutcome.Succeeded)
      val manifest = soleManifest(workDir)
      assertEquals(manifest.outcome, ManifestOutcome.Succeeded)
      assertEquals(
        manifest.sessions.size,
        100,
        "every distinct session must be recorded"
      )

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
    writer.finish(RunOutcome.Failed)
    assertEquals(
      manifestFiles(workDir),
      Nil,
      "no manifest for a run with no committed session"
    )

  test("stage events before the first SessionCommitted are not lost"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    writer.onEvent(OrcaEvent.StageStarted("plan"))
    writer.onEvent(OrcaEvent.StageCompleted("plan"))
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
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
      OrcaEvent.SessionCommitted(
        harness = "claude",
        clientId = "client-1",
        wireId = Some("wire-1"),
        sessionName = None,
        agent = "claude",
        role = None
      )
    )
    val manifest = soleManifest(workDir)
    assertEquals(manifest.flow, Some("review-pr.sc"))
    assertEquals(manifest.workDir, workDir.toString)

  test("a failed write is swallowed and does not stop the next one"):
    val workDir = TempDirs.dir()
    val writer =
      newWriter(workDir, fixedClock(Instant.parse("2026-07-18T10:00:00Z")))
    // A plain file where the runs directory belongs: every write into it fails.
    val runsDir = OrcaDir.cacheRunsPath(workDir)
    os.remove.all(runsDir)
    os.write(runsDir, "not a directory")
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = "claude",
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionName = None,
          agent = "claude",
          role = None
        )
    )
    os.remove(runsDir): Unit
    os.makeDir.all(runsDir)
    writer.finish(RunOutcome.Succeeded)
    assertEquals(soleManifest(workDir).outcome, ManifestOutcome.Succeeded)
