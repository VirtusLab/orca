package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}

import java.time.Instant

/** Decodes manifests as older builds left them on disk (ADR 0021 §8 amendment,
  * 2026-08-05).
  *
  * FROZEN: neither fixture may be edited to accommodate a schema change. They
  * are the only thing standing behind the additive-only rule that replaced
  * `manifestVersion` — every other manifest fixture in the repo is hand-built
  * inside its test and gets updated in the same commit as the schema, so none
  * of them can catch a field becoming required, renamed, or retyped. If one of
  * these fails, the schema change is the bug.
  *
  * Hand-authored to match what an older build emitted, then pretty-printed for
  * readability — the writer itself emits compact JSON. Both carry
  * `manifestVersion`, `cost` and `turns`, none of which this build declares:
  * skipping them is part of what is under test, and they are what an on-disk
  * manifest from before the cost log actually looks like.
  *
  * Compared by whole-value equality rather than field by field: a structural
  * comparison cannot forget a field, so renaming an `Option` — which would
  * otherwise decode as a silent `None` and pass — fails here.
  */
class RunManifestGoldenTest extends munit.FunSuite:

  private def decode(resource: String): RunManifest =
    val text = scala.io.Source.fromResource(s"orca/manifest/$resource").mkString
    readFromString[RunManifest](text)(using RunManifest.codec)

  /** What `golden-run-manifest.json` means, decoded. */
  private val finishedRun: RunManifest =
    RunManifest(
      orcaVersion = "0.1.0",
      flow = Some("implement.sc"),
      workDir = "/home/user/project",
      pid = 4242,
      startedAt = Instant.parse("2026-08-04T09:15:00Z"),
      finishedAt = Some(Instant.parse("2026-08-04T09:48:12Z")),
      outcome = ManifestOutcome.Succeeded,
      sessions = List(
        ManifestSession(
          harness = "claude-code",
          wireId = Some("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"),
          reason = None,
          agent = "implementer",
          role = Some("coding"),
          stage = Some("implement"),
          sessionName = Some("coder"),
          kind = ManifestSessionKind.Durable,
          firstSeenAt = Instant.parse("2026-08-04T09:16:03Z"),
          lastActiveAt = Instant.parse("2026-08-04T09:47:55Z")
        ),
        ManifestSession(
          harness = "codex",
          wireId = None,
          reason = Some("codex sessions do not survive the run"),
          agent = "reviewer-scala-fp",
          role = None,
          stage = None,
          sessionName = None,
          kind = ManifestSessionKind.OneShot,
          firstSeenAt = Instant.parse("2026-08-04T09:30:11Z"),
          lastActiveAt = Instant.parse("2026-08-04T09:30:44Z")
        )
      )
    )

  test("a finished run's manifest from an older build decodes unchanged"):
    assertEquals(decode("golden-run-manifest.json"), finishedRun)

  /** The write side of the same guarantee, which decoding alone can't give:
    * every timestamp and both enums have to leave this build spelled the way
    * the frozen fixture spells them, or the next build's reader is the one that
    * finds out. Compact, since that is what the writer emits.
    */
  test("a manifest encodes to the frozen fixture's wire spellings"):
    assertEquals(
      writeToString(finishedRun)(using RunManifest.codec),
      """{"orcaVersion":"0.1.0","flow":"implement.sc","workDir":"/home/user/project","pid":4242,"startedAt":"2026-08-04T09:15:00Z","finishedAt":"2026-08-04T09:48:12Z","outcome":"succeeded","sessions":[{"harness":"claude-code","wireId":"0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0","agent":"implementer","role":"coding","stage":"implement","sessionName":"coder","kind":"durable","firstSeenAt":"2026-08-04T09:16:03Z","lastActiveAt":"2026-08-04T09:47:55Z"},{"harness":"codex","reason":"codex sessions do not survive the run","agent":"reviewer-scala-fp","kind":"oneShot","firstSeenAt":"2026-08-04T09:30:11Z","lastActiveAt":"2026-08-04T09:30:44Z"}]}"""
    )

  /** The in-flight shape, which the finished fixture cannot cover: `outcome`
    * still `"running"` and `finishedAt` absent. That is what a crashed run
    * leaves behind, and continuing its sessions is the feature this format
    * exists for — so retyping `finishedAt` out of `Option` has to fail here.
    */
  test("a crashed run's manifest from an older build decodes unchanged"):
    assertEquals(
      decode("golden-running-manifest.json"),
      RunManifest(
        orcaVersion = "0.1.0",
        flow = None,
        workDir = "/home/user/project",
        pid = 5150,
        startedAt = Instant.parse("2026-08-04T11:02:00Z"),
        finishedAt = None,
        outcome = ManifestOutcome.Running,
        sessions = List(
          ManifestSession(
            harness = "gemini",
            wireId = None,
            reason = None,
            agent = "planner",
            role = None,
            stage = None,
            sessionName = None,
            kind = ManifestSessionKind.OneShot,
            firstSeenAt = Instant.parse("2026-08-04T11:02:30Z"),
            lastActiveAt = Instant.parse("2026-08-04T11:02:41Z")
          )
        )
      )
    )
