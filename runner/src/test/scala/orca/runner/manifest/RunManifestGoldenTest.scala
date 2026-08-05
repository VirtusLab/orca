package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString

/** Decodes a frozen manifest written by an older build, byte for byte as that
  * build produced it (ADR 0021 §8 amendment, 2026-08-05).
  *
  * FROZEN: `golden-run-manifest.json` must never be edited to accommodate a
  * schema change. It is the only thing standing behind the additive-only rule
  * that replaced `manifestVersion` — every other fixture in the repo is
  * hand-built inside its test and gets updated in the same commit as the
  * schema, so none of them can catch a field becoming required. If this test
  * fails, the schema change is the bug.
  *
  * The file still carries `manifestVersion`, `cost.cost` and per-turn
  * `promptTokens`: fields this build no longer declares, kept precisely because
  * skipping them is the behaviour under test.
  */
class RunManifestGoldenTest extends munit.FunSuite:

  private def golden: RunManifest =
    val text = scala.io.Source
      .fromResource("orca/manifest/golden-run-manifest.json")
      .mkString
    readFromString[RunManifest](text)(using RunManifest.codec)

  test("a manifest from an older build still decodes, unknown fields skipped"):
    assertEquals(golden.orcaVersion, "0.1.0")
    assertEquals(golden.workDir, "/home/user/project")
    assertEquals(golden.pid, 4242L)
    assertEquals(golden.startedAt, "2026-08-04T09:15:00Z")
    assertEquals(golden.outcome, RunManifest.OutcomeSucceeded)

  test("every session field of an older build's manifest survives the decode"):
    val durable = golden.sessions.head
    assertEquals(durable.harness, "claude-code")
    assertEquals(durable.wireId, Some("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
    assertEquals(durable.agent, "implementer")
    assertEquals(durable.role, Some("coding"))
    assertEquals(durable.stage, Some("implement"))
    assertEquals(durable.sessionName, Some("coder"))
    assertEquals(durable.kind, RunManifest.KindDurable)
    assertEquals(durable.firstSeenAt, "2026-08-04T09:16:03Z")
    assertEquals(durable.lastActiveAt, "2026-08-04T09:47:55Z")
    assertEquals(durable.resumable, true)

  /** The wireId-less arm: `reason` present, `role`/`stage`/`sessionName`
    * absent. Absent optionals are how the writer omits `None`, so this pins
    * that an old file's omissions still read as `None` rather than failing.
    */
  test("an older manifest's one-shot session decodes with its fields absent"):
    val oneShot = golden.sessions(1)
    assertEquals(oneShot.wireId, None)
    assertEquals(oneShot.reason, Some("codex sessions do not survive the run"))
    assertEquals(oneShot.role, None)
    assertEquals(oneShot.stage, None)
    assertEquals(oneShot.sessionName, None)
    assertEquals(oneShot.resumable, false)
