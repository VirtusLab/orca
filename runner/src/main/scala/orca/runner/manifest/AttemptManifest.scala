package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{BackendTag, SessionKey}

import java.time.Instant

/** Where an attempt stands, as [[AttemptManifest.status]] records it: `Running`
  * until the attempt records how it ended.
  */
private[orca] enum AttemptStatus:
  case Running, Succeeded, Failed

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
    backend: BackendTag,
    wireId: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    minted: Option[SessionKey],
    lastActiveAt: Instant
):
  def kind: SessionKind =
    if minted.isDefined then SessionKind.Durable else SessionKind.Ephemeral

/** One attempt as the shell's "continue a session" listing sees it, projected
  * from the attempt's events in its run's event log (ADR 0025). A stale
  * [[AttemptStatus.Running]] with a dead `pid` means the attempt crashed, and
  * the shell still offers its recorded sessions.
  *
  * `sessions` is empty until the first `SessionCommitted`; [[continuable]] is
  * what the shell asks.
  *
  * `branch` is the branch the attempt bound to; `None` until `BranchBound`
  * fires, so an attempt that failed before binding has none.
  *
  * `sessions` only grows: a session keeps its position once recorded, which the
  * shell's `orca continue <id>` selector relies on.
  */
private[orca] case class AttemptManifest(
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    branch: Option[String],
    pid: Long,
    startedAt: Instant,
    finishedAt: Option[Instant],
    status: AttemptStatus,
    sessions: List[ManifestSession]
):
  /** Whether the attempt recorded a session the shell can offer to continue. */
  def continuable: Boolean = sessions.nonEmpty
