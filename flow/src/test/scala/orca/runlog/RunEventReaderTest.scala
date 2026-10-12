package orca.runlog

import orca.{AttemptId, StagePath}
import orca.agents.BackendTag
import orca.events.StageOutcome
import orca.gitref.BranchName
import orca.testkit.TempDirs
import ox.tap

import java.time.Instant

class RunEventReaderTest extends munit.FunSuite:

  private val at = Instant.parse("2026-10-08T10:00:00Z")
  private val attempt = AttemptId(Instant.ofEpochMilli(1000), pid = 42)

  private val branch = BranchName.parse("b").fold(e => fail(e), identity)
  private val bound = RunEvent.BranchBound(at, attempt, branch)
  private val finished =
    RunEvent.AttemptFinished(at, attempt, AttemptOutcome.Succeeded)

  private def logOf(lines: String*): os.Path =
    (TempDirs.dir() / "events.jsonl").tap(os.write(_, lines.mkString("\n")))

  test("skips a line of unknown type and a torn last line"):
    val log = logOf(
      RunEvent.encodeLine(bound),
      """{"type":"FromTheFuture","at":"2026-10-08T10:00:00Z","attempt":"1000-42"}""",
      RunEvent.encodeLine(finished),
      RunEvent.encodeLine(bound).take(20)
    )
    assertEquals(RunEventReader.read(log), List(bound, finished))

  test("ignores an unknown field"):
    val log = logOf(
      """{"type":"BranchBound","at":"2026-10-08T10:00:00Z","attempt":"1000-42","branch":"b","extra":{"x":1}}"""
    )
    assertEquals(RunEventReader.read(log), List(bound))

  test("an absent file has no events"):
    assertEquals(RunEventReader.read(TempDirs.dir() / "events.jsonl"), Nil)

  test("an unreadable file has no events"):
    val dir = TempDirs.dir() / "events.jsonl"
    os.makeDir(dir)
    assertEquals(RunEventReader.read(dir), Nil)

  test("readOnly keeps only the events of the given types"):
    val log = logOf(
      RunEvent.encodeLine(bound),
      RunEvent.encodeLine(finished),
      RunEvent.encodeLine(bound)
    )
    assertEquals(
      RunEventReader.readOnly(log, Set(classOf[RunEvent.AttemptFinished])),
      List(finished)
    )

  // `readOnly` matches a line's `type` against the class name, so this pins
  // that the codec writes each case under that name.
  test("readOnly finds every case by its class"):
    val stage = StagePath.FlowBody.child("s", 0)
    val events = List(
      RunEvent.AttemptStarted(at, attempt, 1, "v", None, "/w", 42, None),
      bound,
      RunEvent.StageStarted(at, attempt, stage),
      RunEvent.StageEnded(at, attempt, stage, StageOutcome.Completed),
      RunEvent.SessionMinted(
        at,
        attempt,
        "n",
        StagePath.FlowBody,
        "id",
        "seed",
        BackendTag.Pi
      ),
      RunEvent.SessionWireId(at, attempt, "id", "w"),
      RunEvent.SessionCommitted(
        at,
        attempt,
        BackendTag.Pi,
        None,
        "c",
        "a",
        None,
        None,
        None
      ),
      RunEvent.Turn(
        at,
        attempt,
        "a",
        None,
        None,
        None,
        1,
        None,
        TurnUsage(0, 0, 0, 0, 0),
        None,
        "c"
      ),
      RunEvent.RunSucceeded(at, attempt, branch, None),
      finished
    )
    val log = logOf(events.map(RunEvent.encodeLine)*)
    events.foreach(e =>
      assertEquals(RunEventReader.readOnly(log, Set(e.getClass)), List(e))
    )
