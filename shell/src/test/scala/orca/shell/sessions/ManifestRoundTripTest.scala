package orca.shell.sessions

import orca.events.OrcaEvent
import orca.testkit.Usages.usage
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
          harness = "claude",
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionName = Some("coder"),
          agent = "claude",
          role = None
        )
      )
      writer.onEvent(
        OrcaEvent.TokensUsed(
          "claude",
          None,
          usage(1_000, 200, Some(BigDecimal("0.5"))),
          Some("reviewer"),
          cost = None
        )
      )
      writer.finish(RunOutcome.Succeeded)

    val (runs, warnings) =
      ManifestReader.list(workDir, Nil, pidAlive = _ => true)
    assertEquals(warnings, Nil)
    assertEquals(runs.size, 1)
    assertEquals(runs.head.crashed, false)
    val session = runs.head.manifest.sessions.head
    assertEquals(session.harness, "claude")
    assertEquals(session.wireId, Some("wire-1"))
    assertEquals(session.resumable, true)
    assertEquals(session.sessionName, Some("coder"))
    assertEquals(session.stage, Some("code"))
    // The same run wrote a `-cost.jsonl` beside the manifest (the TokensUsed
    // above). The shell selects by `ext == "json"`, so it must not appear as a
    // manifest that fails to decode — `warnings` being empty is that check.
    assert(
      os.list(workDir / ".orca" / "cache" / "runs")
        .exists(_.last.endsWith("-cost.jsonl")),
      "expected the run's cost log beside its manifest"
    )
