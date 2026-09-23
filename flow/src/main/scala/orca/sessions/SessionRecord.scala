package orca.sessions

import orca.StagePath
import orca.agents.{BackendTag, SessionKey}

/** A durable session as it is stored: the [[SessionKey]] halves that key it —
  * the name, and the path of the stage that minted it — a minted UUID, the seed
  * string the author supplied, and, once a turn has committed, the wire id to
  * resume the live backend conversation against.
  *
  * `id` is the stable client id the framework hands across calls;
  * `resumeWireId` is the id to put on the wire when resuming (same `wireId`
  * notion as [[orca.backend.Dispatch]]). Its value depends on the backend:
  *   - codex/gemini/opencode: a backend-minted server-thread id (≠ `id`);
  *   - claude/pi: equal to `id` itself — both claim the id client-side and keep
  *     a durable transcript, so recording it re-claims the session (`--resume`
  *     / `--continue`) on a resumed run.
  *
  * `backend` records the minting agent's [[orca.agents.BackendTag]]:
  * `agent.session(name, seed)` reuses the record only for an agent with the
  * same tag, and mints fresh otherwise. `None` when the minting agent carries
  * no backend tag (a stub agent); any agent then reuses it.
  */
case class SessionRecord(
    name: String,
    stage: StagePath,
    id: String,
    seed: String,
    resumeWireId: Option[String],
    backend: Option[BackendTag]
):
  /** The key this record is stored under. */
  def key: SessionKey = SessionKey(name = name, stage = stage)
