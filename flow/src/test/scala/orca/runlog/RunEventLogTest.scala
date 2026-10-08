package orca.runlog

import orca.{AttemptId, OrcaDir, RunKey, StagePath}
import orca.agents.BackendTag
import orca.events.{OrcaEvent, StageOutcome, Usage}
import orca.gitref.BranchName
import orca.sessions.SessionRecord
import orca.testkit.TempDirs
import ox.supervised
import ox.channels.BufferCapacity

import java.time.Instant

class RunEventLogTest extends munit.FunSuite:

  private val at = Instant.parse("2026-10-08T10:00:00Z")
  private val attempt = AttemptId(Instant.ofEpochMilli(1000), pid = 42)
  private val key = RunKey.of("prompt")

  private val outer = StagePath.FlowBody.child("review", 0)
  private val inner = outer.child("fix", 1)

  private val record = SessionRecord(
    name = "implementer",
    stage = StagePath.FlowBody,
    id = "id-1",
    seed = "seed",
    resumeWireId = None,
    backend = BackendTag.Pi
  )

  private val mintedEvent: RunEvent.SessionMinted = RunEvent.SessionMinted(
    at,
    attempt,
    name = "implementer",
    stage = StagePath.FlowBody,
    id = "id-1",
    seed = "seed",
    backend = BackendTag.Pi
  )

  private val wireIdEvent =
    RunEvent.SessionWireId(at, attempt, id = "id-1", wireId = "w")

  private def branch(raw: String): BranchName =
    BranchName.parse(raw).fold(e => fail(e), identity)

  private val committed = OrcaEvent.SessionCommitted(
    BackendTag.Pi,
    clientId = "c",
    wireId = None,
    sessionKey = None,
    agent = "pi",
    role = None
  )

  private val tokens = OrcaEvent.TokensUsed(
    OrcaEvent.UnpricedTurn("pi", None, Usage.empty, None, 1, "c"),
    None
  )

  /** Runs `body` against a log started in `dir` and finishes it as succeeded.
    */
  private def withLog(dir: os.Path)(body: RunEventLog => Unit): Unit =
    supervised:
      val log = started(dir)
      body(log)
      log.finish(AttemptOutcome.Succeeded)

  private def started(dir: os.Path)(using ox.Ox): RunEventLog =
    given BufferCapacity = BufferCapacity(8)
    RunEventLog.start(
      dir,
      key,
      attempt,
      "1.2.3",
      Some("implement.sc"),
      Some(dir / "t.log"),
      () => at
    )

  /** [[withLog]], then the events `body` wrote: those between `AttemptStarted`
    * and `AttemptFinished`.
    */
  private def logged(dir: os.Path)(body: RunEventLog => Unit): List[RunEvent] =
    withLog(dir)(body)
    fileEvents(dir).drop(1).dropRight(1)

  private def fileEvents(dir: os.Path): List[RunEvent] =
    RunEventReader.read(OrcaDir.eventLogPath(dir, key))

  test("the log starts with AttemptStarted carrying the schema"):
    val dir = TempDirs.dir()
    withLog(dir)(_ => ())
    assertEquals(
      fileEvents(dir).headOption,
      Some(
        RunEvent.AttemptStarted(
          at,
          attempt,
          schema = 1,
          orcaVersion = "1.2.3",
          flow = Some("implement.sc"),
          workDir = dir.toString,
          pid = 42,
          trace = Some((dir / "t.log").toString)
        )
      )
    )

  test("events land in emission order with full stage paths"):
    val events = logged(TempDirs.dir()): log =>
      log.onEvent(OrcaEvent.StageStarted(outer))
      log.onEvent(OrcaEvent.BranchBound("feature/x"))
      log.onEvent(OrcaEvent.StageStarted(inner))
      log.onEvent(OrcaEvent.StageEnded(inner, StageOutcome.Failed))
    assertEquals(
      events,
      List(
        RunEvent.StageStarted(at, attempt, outer),
        RunEvent.BranchBound(at, attempt, branch("feature/x")),
        RunEvent.StageStarted(at, attempt, inner),
        RunEvent.StageEnded(at, attempt, inner, StageOutcome.Failed)
      )
    )

  test("SessionCommitted and Turn carry the innermost open stage"):
    val events = logged(TempDirs.dir()): log =>
      log.onEvent(OrcaEvent.StageStarted(outer))
      log.onEvent(OrcaEvent.StageStarted(inner))
      log.onEvent(committed)
      log.onEvent(tokens)
    assertEquals(
      events.collect:
        case e: RunEvent.SessionCommitted => e.stage
        case e: RunEvent.Turn             => e.stage
      ,
      List(Some(inner), Some(inner))
    )

  test("SessionCommitted and Turn carry no stage outside any stage"):
    val events = logged(TempDirs.dir()): log =>
      log.onEvent(OrcaEvent.StageStarted(outer))
      log.onEvent(OrcaEvent.StageEnded(outer, StageOutcome.Completed))
      log.onEvent(committed)
      log.onEvent(tokens)
    assertEquals(
      events.collect:
        case e: RunEvent.SessionCommitted => e.stage
        case e: RunEvent.Turn             => e.stage
      ,
      List(None, None)
    )

  test("upserting a new key with a wire id writes SessionMinted and its id"):
    val events = logged(TempDirs.dir()):
      _.upsert(record.copy(resumeWireId = Some("w")))
    assertEquals(
      events,
      List(mintedEvent, wireIdEvent)
    )

  test("upserting an identical record writes nothing"):
    val events = logged(TempDirs.dir()): log =>
      log.upsert(record)
      log.upsert(record)
    assertEquals(events, List(mintedEvent))

  test("upserting a changed wire id writes only SessionWireId"):
    val events = logged(TempDirs.dir()): log =>
      log.upsert(record)
      log.upsert(record.copy(resumeWireId = Some("w")))
    assertEquals(
      events,
      List(mintedEvent, wireIdEvent)
    )

  test("upserting a known key with a new id re-mints it"):
    val remint = record.copy(id = "id-2", seed = "seed-2")
    val events = logged(TempDirs.dir()): log =>
      log.upsert(record)
      log.upsert(remint)
      assertEquals(log.records(), List(remint))
    assertEquals(
      events,
      List(mintedEvent, mintedEvent.copy(id = "id-2", seed = "seed-2"))
    )

  test("runSucceeded writes RunSucceeded and empties the records"):
    val events = logged(TempDirs.dir()): log =>
      log.upsert(record)
      log.runSucceeded(branch("feature/x"), Some("#12"))
      assertEquals(log.records(), Nil)
    assertEquals(
      events,
      List(
        mintedEvent,
        RunEvent.RunSucceeded(at, attempt, branch("feature/x"), Some("#12"))
      )
    )

  test("a BranchBound whose branch does not parse is not recorded"):
    val events = logged(TempDirs.dir()): log =>
      log.onEvent(OrcaEvent.BranchBound("not a..branch"))
      log.onEvent(OrcaEvent.StageStarted(outer))
    assertEquals(events, List(RunEvent.StageStarted(at, attempt, outer)))

  test("records reflect an earlier upsert"):
    withLog(TempDirs.dir()): log =>
      log.upsert(record)
      assertEquals(log.records(), List(record))

  test("a success in an earlier log hides the sessions before it"):
    val dir = TempDirs.dir()
    val later = record.copy(name = "reviewer", id = "id-2")
    withLog(dir): log =>
      log.upsert(record)
      log.runSucceeded(branch("feature/x"), None)
      log.upsert(later)
    withLog(dir)(log => assertEquals(log.records(), List(later)))

  test("the file is complete when finish returns"):
    val dir = TempDirs.dir()
    supervised:
      val log = started(dir)
      for _ <- 1 to 50 do
        log.onEvent(OrcaEvent.StageStarted(outer))
        log.onEvent(OrcaEvent.StageEnded(outer, StageOutcome.Completed))
      log.finish(AttemptOutcome.Succeeded)
      val events = fileEvents(dir)
      assertEquals(events.size, 102)
      assertEquals(
        events.lastOption,
        Some(RunEvent.AttemptFinished(at, attempt, AttemptOutcome.Succeeded))
      )

  test("a torn last line does not swallow the next AttemptStarted"):
    val dir = TempDirs.dir()
    os.write(
      OrcaDir.eventLogPath(dir, key),
      """{"type":"StageStarted","at":""",
      createFolders = true
    )
    withLog(dir)(_ => ())
    assert(
      fileEvents(dir).headOption.exists(_.isInstanceOf[RunEvent.AttemptStarted])
    )

  test("an unwritable event log neither throws nor stops later calls"):
    val dir = TempDirs.dir()
    os.makeDir.all(OrcaDir.eventLogPath(dir, key))
    withLog(dir): log =>
      log.onEvent(committed)
      log.upsert(record)
      assertEquals(log.records(), List(record))

  test("start removes the cache files of older versions"):
    val dir = TempDirs.dir()
    val cache = OrcaDir.cachePath(dir)
    os.write(
      cache / "attempts" / "1-1.manifest.json",
      "{}",
      createFolders = true
    )
    os.write(cache / "runs" / "abc.sessions.json", "[]", createFolders = true)
    withLog(dir)(_ => ())
    assert(!os.exists(cache / "attempts"))
    assert(!os.exists(cache / "runs" / "abc.sessions.json"))
