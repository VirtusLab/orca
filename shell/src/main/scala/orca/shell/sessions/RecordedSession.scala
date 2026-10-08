package orca.shell.sessions

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{BackendTag, SessionKey}

import java.time.Instant

/** How a recorded session was opened, as [[RecordedSession.kind]] reads it:
  * under an `agent.session(name, seed)` key, or as an ephemeral `run`/`chat`
  * conversation minted under no key. The codec is for the `continue --list
  * --json` rows.
  */
private[shell] enum SessionKind:
  case Durable, Ephemeral

private[shell] object SessionKind:
  given codec: ConfiguredJsonValueCodec[SessionKind] =
    ConfiguredJsonValueCodec.derived[SessionKind](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )

/** One session an attempt recorded. `wireId` is the persistable id
  * ([[orca.agents.Agent.resumeWireId]]) — `None` when the id isn't known yet,
  * which is what makes the session not resumable; it never means the backend
  * can't resume.
  *
  * `minted` is the key a flow minted a durable session under; `None` for an
  * ephemeral session, minted under no key.
  *
  * `stage` is where the session was last active, re-stamped on every turn;
  * `minted.stage` is the stage that minted it and never changes.
  */
private[shell] case class RecordedSession(
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
