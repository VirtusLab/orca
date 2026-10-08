package orca.shell.sessions

import orca.{AttemptId, RunKey, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.events.OrcaEvent
import orca.runlog.{AttemptOutcome, RunEventLog}
import orca.testkit.{StageEvents, TempDirs}
import ox.channels.BufferCapacity
import ox.supervised

import java.time.Instant

/** One round trip through the REAL codecs on both ends: [[RunEventLog]] (the
  * production writer `flow()` attaches) writes a session to disk, then
  * [[EventLogReader.list]] reads it back, so a schema drift between the two is
  * caught here.
  */
class EventLogRoundTripTest extends munit.FunSuite:

  test("a durable session survives the real writer -> real reader round trip"):
    val workDir = TempDirs.dir()
    val coderKey =
      SessionKey(name = "coder", stage = StagePath.FlowBody.child("Task 2", 0))
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val log = RunEventLog.start(
        workDir,
        RunKey.of("a prompt"),
        AttemptId(Instant.now(), pid = 1),
        "0.0.test",
        Some("a-flow.sc"),
        None,
        () => Instant.now()
      )
      log.onEvent(StageEvents.started("code"))
      log.onEvent(
        OrcaEvent.SessionCommitted(
          backend = BackendTag.ClaudeCode,
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionKey = Some(coderKey),
          agent = "claude",
          role = None
        )
      )
      log.finish(AttemptOutcome.Succeeded)

    val AttemptListing(attempts, warnings) =
      EventLogReader.list(workDir, Nil, processAlive = _ => true)
    assertEquals(warnings, Nil)
    assertEquals(attempts.map(_.observedStatus), List(ObservedStatus.Succeeded))
    assertEquals(
      attempts
        .flatMap(_.record.sessions)
        .map(s => (s.backend, s.wireId, s.minted, s.stage)),
      List(
        (BackendTag.ClaudeCode, Some("wire-1"), Some(coderKey), Some("code"))
      )
    )
