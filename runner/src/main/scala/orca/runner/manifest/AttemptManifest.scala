package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{BackendTag, JsonData, SessionKey}

import java.time.Instant

/** Where an attempt stands, as [[AttemptManifest.status]] records it: `Running`
  * until [[AttemptManifestWriter.finish]] records how it ended.
  */
private[orca] enum AttemptStatus:
  case Running, Succeeded, Failed

private[orca] object AttemptStatus:
  given codec: ConfiguredJsonValueCodec[AttemptStatus] =
    ConfiguredJsonValueCodec.derived[AttemptStatus](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )

/** How a manifest session was opened, as [[ManifestSession.kind]] reads it:
  * under an `agent.session(name, seed)` key, or as an ephemeral `run`/`chat`
  * conversation minted under no key. Not persisted; the codec is for the
  * shell's `continue --list --json` rows.
  */
private[orca] enum SessionKind:
  case Durable, Ephemeral

private[orca] object SessionKind:
  given codec: ConfiguredJsonValueCodec[SessionKind] =
    ConfiguredJsonValueCodec.derived[SessionKind](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )

/** One tracked session inside a [[AttemptManifest]] (ADR 0021 §8). `wireId` is
  * the persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` when the id
  * isn't known yet, which is what makes the session not resumable; it never
  * means the backend can't resume.
  *
  * `minted` is the key a flow minted a durable session under; `None` for an
  * ephemeral session, minted under no key.
  *
  * `stage` is where the session was last active, re-stamped on every turn;
  * `minted.stage` is the stage that minted it and never changes.
  */
private[orca] case class ManifestSession(
    harness: BackendTag,
    wireId: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    minted: Option[SessionKey],
    lastActiveAt: Instant
):
  def kind: SessionKind =
    if minted.isDefined then SessionKind.Durable else SessionKind.Ephemeral

/** A per-attempt manifest written to
  * `.orca/cache/attempts/<AttemptId>.manifest.json`, read by the shell to offer
  * "continue a session". A stale [[AttemptStatus.Running]] with a dead `pid`
  * means the attempt crashed, and the shell still offers its recorded sessions.
  *
  * Written from the attempt's start, so `sessions` is empty until the first
  * `SessionCommitted`; [[continuable]] is what the shell and pruning ask.
  *
  * Carries no cost or turn data: that lives in the attempt's
  * `<AttemptId>.cost.jsonl` ([[CostLog]]), which this file neither references
  * nor requires.
  */
private[orca] case class AttemptManifest(
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    pid: Long,
    startedAt: Instant,
    finishedAt: Option[Instant],
    status: AttemptStatus,
    sessions: List[ManifestSession]
):
  /** Whether the attempt recorded a session the shell can offer to continue. */
  def continuable: Boolean = sessions.nonEmpty

private[orca] object AttemptManifest:
  // Only a jsoniter codec — no `JsonData`/`Schema` half, deliberately: the
  // manifest crosses the process/disk boundary to the shell, never an HTTP or
  // LLM boundary, so it needs on-disk (de)serialisation but no tool schema.
  //
  // Strict (`JsonData.strictCodecConfig`): `sessions` absent must not read as
  // empty, since the menu renders the count verbatim.
  given codec: ConfiguredJsonValueCodec[AttemptManifest] =
    ConfiguredJsonValueCodec.derived[AttemptManifest](using
      JsonData.strictCodecConfig
    )
