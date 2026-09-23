package orca.sessions

import orca.StagePath
import orca.agents.{BackendTag, SessionKey}

/** A durable session as it is stored: the [[SessionKey]] halves that key it —
  * the name, and the path id of the stage that minted it — a minted UUID, the
  * seed string the author supplied, and, once a turn has committed, the wire id
  * to resume the live backend conversation against.
  *
  * `id` is the stable client id the framework hands across calls;
  * `resumeWireId` is the id to put on the wire when resuming (same `wireId`
  * notion as [[orca.backend.Dispatch]]). Its value depends on the backend:
  *   - codex/gemini/opencode: a backend-minted server-thread id (≠ `id`);
  *   - claude/pi: equal to `id` itself — both claim the id client-side and keep
  *     a durable transcript, so recording it re-claims the session (`--resume`
  *     / `--continue`) on a resumed run.
  *
  * `backend` records the minting agent's [[orca.agents.BackendTag]], so
  * targeted rehydration (`FlowLifecycle.rehydrateSessions`) knows which agent
  * to replay `resumeWireId` into rather than assuming the lead. `None` when the
  * minting agent carries no backend tag (a stub agent) — falls back to the
  * lead. `agent.session(name, seed)`'s reuse arm self-heals a stale tag from a
  * lead-backend swap.
  */
case class SessionRecord(
    name: String,
    stage: String,
    id: String,
    seed: String,
    resumeWireId: Option[String],
    backend: Option[BackendTag]
):
  /** The key this record is stored under. The single place a persisted stage id
    * is read back into a [[StagePath]].
    */
  def key: SessionKey =
    SessionKey(name = name, stage = StagePath.fromValue(stage))
