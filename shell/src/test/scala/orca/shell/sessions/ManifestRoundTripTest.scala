package orca.shell.sessions

import orca.{AttemptId, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.events.OrcaEvent
import orca.runner.manifest.{AttemptManifestWriter, AttemptOutcome}
import orca.testkit.{StageEvents, TempDirs}
import ox.channels.BufferCapacity
import ox.supervised

import java.time.Instant

/** One round trip through the REAL codecs on both ends:
  * [[AttemptManifestWriter]] (the production listener `flow()` attaches) writes
  * a session to disk, then [[ManifestReader.list]] reads it back. Every other
  * `ManifestReaderTest` case hand-builds its JSON fixture directly, so a schema
  * drift between the writer and the reader (a renamed field, a codec config
  * mismatch) would go undetected without this.
  */
class ManifestRoundTripTest extends munit.FunSuite:

  test(
    "a durable, resumable session survives the real writer -> real reader round trip"
  ):
    val workDir = TempDirs.dir()
    val coderKey =
      SessionKey(name = "coder", stage = StagePath.FlowBody.child("Task 2", 0))
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val writer = AttemptManifestWriter.start(
        workDir,
        "0.0.test",
        Some("a-flow.sc"),
        AttemptId(Instant.now(), pid = 1),
        () => Instant.now()
      )
      writer.onEvent(StageEvents.started("code"))
      writer.onEvent(
        OrcaEvent.SessionCommitted(
          backend = BackendTag.ClaudeCode,
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionKey = Some(coderKey),
          agent = "claude",
          role = None
        )
      )
      writer.finish(AttemptOutcome.Succeeded)

    val AttemptListing(attempts, warnings) =
      ManifestReader.list(workDir, Nil, pidAlive = _ => true)
    assertEquals(warnings, Nil)
    assertEquals(attempts.size, 1)
    assertEquals(attempts.head.crashed, false)
    val session = attempts.head.manifest.sessions.head
    assertEquals(session.backend, BackendTag.ClaudeCode)
    assertEquals(session.wireId, Some("wire-1"))
    assertEquals(session.minted, Some(coderKey))
    assertEquals(session.stage, Some("code"))
