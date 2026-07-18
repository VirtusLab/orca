package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{JsonData, given}
import orca.util.RawJson
import sttp.tapir.Schema

/** Header capturing the git context in which the progress log was started. */
case class ProgressHeader(
    startingBranch: String,
    branch: String,
    promptHash: String
) derives JsonData

/** A single stage's outcome, stored as an already-serialised JSON subtree.
  *
  * `id` is the stage's hierarchical path id — `name#occurrence` segments joined
  * by `/` (e.g. `outer#0/inner#0`), a nested stage prefixed by its enclosing
  * stages' segments (ADR 0018 §2.1). Opaque: only compared for exact equality,
  * never parsed.
  *
  * `resultJson` is type-erased at rest — the log is heterogeneous across stage
  * types; deserialisation to a typed value happens at the stage call site. A
  * [[orca.util.RawJson]], embedded verbatim rather than string-escaped so the
  * persisted file stays directly readable when debugging.
  */
case class StageEntry(id: String, name: String, resultJson: RawJson)
    derives JsonData

/** A persisted session: the name + occurrence that key it (stage-style — see
  * [[orca.FlowControl.nextSessionOccurrence]]), a minted UUID, the seed string
  * the author supplied, and — when the session is durably resumable — the wire
  * id to resume against.
  *
  * `id` is the stable client id the framework hands across calls;
  * [[SessionRecord.resumeWireId]] is the id to put on the wire when resuming
  * the live backend conversation (same `wireId` notion as
  * [[orca.backend.Dispatch]]). Its value depends on the backend:
  *   - codex/gemini/opencode: a backend-minted server-thread id (≠ `id`);
  *   - claude: equal to `id` itself — claude's sessions are durable on disk, so
  *     recording it re-claims the session (`--resume`) on a resumed run;
  *   - pi: `None` — pi's sessions live in a `deleteOnExit` temp dir, so a
  *     resumed run always re-seeds.
  *
  * Persisted so a resumed run can rehydrate the in-memory map, resume against
  * the right wire id, and reuse the same [[orca.agents.SessionId]] rather than
  * minting a second one. `resumeWireId` is `None` until a run learns it (see
  * `persistResumeWireId` in `orca.Session`).
  *
  * `backend` records the minting agent's [[orca.agents.BackendTag]] via its
  * stable [[orca.agents.BackendTag.wireName]] (frozen independently of the case
  * name), so targeted rehydration (`FlowLifecycle.rehydrateSessions`) knows
  * which agent to replay `resumeWireId` into rather than assuming the lead.
  * `None` when the minting agent carries no backend tag (a stub agent) — falls
  * back to the lead. A value matching no known `wireName` (an edited log) is
  * skipped with a warning rather than guessed (`FlowLifecycle.targetAgent`);
  * `agent.session(name, seed)`'s reuse arm self-heals a stale tag from a
  * lead-backend swap.
  */
case class SessionRecord(
    name: String,
    occurrence: Int,
    id: String,
    seed: String,
    resumeWireId: Option[String] = None,
    backend: Option[String] = None
) derives JsonData

/** An append-only log of stage outcomes and session records for one flow run,
  * keyed by its header. The custom [[JsonData]] instance below tolerates
  * missing collection fields so logs round-trip across software versions.
  */
case class ProgressLog(
    header: ProgressHeader,
    entries: List[StageEntry],
    sessions: List[SessionRecord] = Nil
)

object ProgressLog:
  /** Does not require collection fields to be present, diverging from
    * `JsonData.strictCodecConfig` (`withRequireCollectionFields(true)`). Strict
    * is right for LLM-reply DTOs where a missing list signals a model error,
    * but wrong for the progress log, which must round-trip across versions that
    * add optional fields over time.
    */
  given JsonData[ProgressLog] = JsonData(
    Schema.derived[ProgressLog],
    ConfiguredJsonValueCodec.derived[ProgressLog](using
      CodecMakerConfig
        .withRequireCollectionFields(false)
        .withTransientEmpty(false)
    )
  )
