package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.JsonData

/** One tracked session inside a [[RunManifest]] (ADR 0021 §8). `wireId` is the
  * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` for backends
  * whose sessions don't survive the run (pi), which is exactly when
  * [[resumable]] is `false` and `reason` explains why. `kind` is `"durable"`
  * when the writer joins `clientId` to a `SessionRecord` in the progress log
  * (an `agent.session(...)` call), `"oneShot"` otherwise — a plain
  * `agent.run`/`resultAs` one-shot AND an interactive call both land as
  * `"oneShot"` today, since `SessionCommitted` carries nothing that tells them
  * apart (see [[RunManifestWriterState.durableSessionName]]).
  */
private[orca] case class ManifestSession(
    harness: String,
    wireId: Option[String],
    reason: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    sessionName: Option[String],
    kind: String,
    firstSeenAt: String,
    lastActiveAt: String
):
  /** Derived, not persisted: a session is resumable exactly when it carries a
    * wire id, so there is nothing here that could drift from [[wireId]].
    */
  def resumable: Boolean = wireId.isDefined

/** Schema v2 (ADR 0021 §8) of a per-run manifest written to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`, read by the shell to
  * offer "continue a session". `manifestVersion` is a hard gate: a shell that
  * doesn't understand a newer version skips the file rather than guessing —
  * that gate is the one place cross-version tolerance lives, so the codec below
  * doesn't need to be tolerant too. `outcome` is `"running"` until
  * [[RunManifestWriter.finish]] finalizes it to `"succeeded"` or `"failed"` — a
  * stale `"running"` with a dead `pid` means the run crashed, and the shell
  * still offers its recorded sessions.
  */
private[orca] case class RunManifest(
    manifestVersion: Int,
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    pid: Long,
    startedAt: String,
    finishedAt: Option[String],
    outcome: String,
    sessions: List[ManifestSession]
)

private[orca] object RunManifest:
  /** The manifest schema version this build reads and writes — bumped whenever
    * the wire shape changes (v2 dropped [[ManifestSession.resumable]] as a
    * persisted field). [[ManifestReader]] skips a file whose version exceeds
    * this rather than guessing at a newer schema. The writer
    * ([[RunManifestWriterState.write]]) always passes this explicitly rather
    * than relying on a default, so a version bump can't silently stamp new
    * manifests without the writer call site being revisited.
    */
  val SupportedVersion = 2

  // The `outcome` and `ManifestSession.kind` wire strings, named once here so
  // the writer that produces them (RunManifestWriter's Outcome/SessionKind
  // enums) and every reader that matches on them share one spelling.
  val OutcomeRunning = "running"
  val OutcomeSucceeded = "succeeded"
  val OutcomeFailed = "failed"
  val KindDurable = "durable"
  val KindOneShot = "oneShot"
  val KindInteractive = "interactive"

  // Only a jsoniter codec — no `JsonData`/`Schema` half, deliberately: the
  // manifest crosses the process/disk boundary to the shell, never an HTTP or
  // LLM boundary, so it needs on-disk (de)serialisation but no tool schema.
  // Strict, like every other in-process codec (`JsonData.strictCodecConfig`):
  // a missing collection field fails to parse rather than silently defaulting
  // to empty. The `manifestVersion` gate above is the only cross-version
  // tolerance this format needs — a writer bug producing a malformed manifest
  // should fail loudly, not decode as a plausible-looking empty one.
  given codec: ConfiguredJsonValueCodec[RunManifest] =
    ConfiguredJsonValueCodec.derived[RunManifest](using
      JsonData.strictCodecConfig
    )
