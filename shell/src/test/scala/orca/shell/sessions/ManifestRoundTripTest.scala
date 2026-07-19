package orca.shell.sessions

import orca.WorkspaceWrite
import orca.events.OrcaEvent
import orca.progress.{ProgressHeader, ProgressStore, SessionRecord}
import orca.runner.{RunManifestWriter, RunOutcome}
import orca.testkit.TempDirs
import ox.channels.BufferCapacity
import ox.supervised

import java.time.Instant

/** One round trip through the REAL codecs on both ends: [[RunManifestWriter]]
  * (the production listener `flow()` attaches) writes a session to disk, then
  * [[ManifestReader.list]] reads it back. Every other `ManifestReaderTest` case
  * hand-builds its JSON fixture directly, so a schema drift between the writer
  * and the reader (a renamed field, a codec config mismatch) would go
  * undetected without this.
  */
class ManifestRoundTripTest extends munit.FunSuite:

  test(
    "a durable, resumable session survives the real writer -> real reader round trip"
  ):
    val workDir = TempDirs.dir()
    given WorkspaceWrite = WorkspaceWrite.unsafe
    val store = ProgressStore.default(workDir, "join-prompt")
    store.writeHeader(
      ProgressHeader(
        startingBranch = "main",
        branch = "main",
        promptHash = "abc"
      )
    )
    store.upsertSession(
      SessionRecord(name = "coder", occurrence = 0, id = "client-1", seed = "s")
    )

    supervised:
      given BufferCapacity = BufferCapacity(8)
      val writer = RunManifestWriter.start(
        workDir,
        "0.0.test",
        Some("a-flow.sc"),
        () => Instant.now()
      )
      writer.onEvent(OrcaEvent.StageStarted("code"))
      writer.onEvent(
        OrcaEvent.SessionCommitted(
          "claude",
          "client-1",
          Some("wire-1"),
          "claude",
          None
        )
      )
      writer.finish(RunOutcome.Succeeded)

    val (runs, warnings) = ManifestReader.list(workDir, pidAlive = _ => true)
    assertEquals(warnings, Nil)
    assertEquals(runs.size, 1)
    assertEquals(runs.head.crashed, false)
    val session = runs.head.manifest.sessions.head
    assertEquals(session.harness, "claude")
    assertEquals(session.wireId, Some("wire-1"))
    assertEquals(session.resumable, true)
    assertEquals(session.sessionName, Some("coder"))
    assertEquals(session.stage, Some("code"))
