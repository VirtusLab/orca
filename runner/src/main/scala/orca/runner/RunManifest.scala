package orca.runner

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}

/** One tracked session inside a [[RunManifest]] (ADR 0021 §8). `wireId` is the
  * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` for backends
  * whose sessions don't survive the run (pi), which is exactly when `resumable`
  * is `false` and `reason` explains why. `kind` is `"durable"` when the writer
  * joins `clientId` to a `SessionRecord` in the progress log (an
  * `agent.session(...)` call), `"oneShot"` otherwise — a plain
  * `agent.run`/`resultAs` one-shot AND an interactive call both land as
  * `"oneShot"` today, since `SessionCommitted` carries nothing that tells them
  * apart (see [[RunManifestWriterState.durableSessionName]]).
  */
private[orca] case class ManifestSession(
    harness: String,
    wireId: Option[String],
    resumable: Boolean,
    reason: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    sessionName: Option[String],
    kind: String,
    firstSeenAt: String,
    lastActiveAt: String
)

/** Schema v1 (research 08 §4, ADR 0021 §8) of a per-run manifest written to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`, read by the shell to
  * offer "continue a session". `manifestVersion` is a hard gate: a shell that
  * doesn't understand a newer version skips the file rather than guessing.
  * `outcome` is `"running"` until [[RunManifestWriter.finish]] finalizes it to
  * `"succeeded"` or `"failed"` — a stale `"running"` with a dead `pid` means
  * the run crashed, and the shell still offers its recorded sessions.
  */
private[orca] case class RunManifest(
    manifestVersion: Int = 1,
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
  // Only a jsoniter codec — no `JsonData`/`Schema` half, deliberately: the
  // manifest crosses the process/disk boundary to the shell, never an HTTP or
  // LLM boundary, so it needs on-disk (de)serialisation but no tool schema.
  // Tolerant like ProgressLog's codec (ProgressLog.scala): missing collection
  // fields default to empty rather than failing, so a manifest written by an
  // older or newer orca still parses.
  given codec: ConfiguredJsonValueCodec[RunManifest] =
    ConfiguredJsonValueCodec.derived[RunManifest](using
      CodecMakerConfig
        .withRequireCollectionFields(false)
        .withTransientEmpty(false)
    )
