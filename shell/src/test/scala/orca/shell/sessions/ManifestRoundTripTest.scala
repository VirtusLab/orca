package orca.shell.sessions

import orca.WorkspaceWrite
import orca.events.{OrcaEvent, Pricing, Usage}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore, SessionRecord}
import orca.runner.manifest.{RunManifestWriter, RunOutcome}
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
        promptHash = "abc",
        branchMode = BranchMode.Created
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
        Pricing.default,
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
      writer.onEvent(
        OrcaEvent.TokensUsed(
          "claude",
          None,
          Usage(1_000, 200, Some(BigDecimal("0.5"))),
          Some("reviewer")
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
    val cost = runs.head.manifest.cost
    assertEquals(cost.total.inputTokens, 1_000L)
    assertEquals(cost.byRole.map(_.key), List(Some("reviewer")))
    assertEquals(runs.head.manifest.turns.map(_.promptTokens), List(1_000L))
