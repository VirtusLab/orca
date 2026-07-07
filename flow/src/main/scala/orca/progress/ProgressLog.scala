package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{JsonData, given}
import sttp.tapir.Schema

/** Header capturing the git context in which the progress log was started. */
case class ProgressHeader(
    startingBranch: String,
    branch: String,
    promptHash: String
) derives JsonData

/** A single stage's outcome, stored as an already-serialised JSON string.
  *
  * `id` is the stage's hierarchical path id — `name#occurrence` segments joined
  * by `/` (e.g. `outer#0/inner#0`), where a nested stage carries its enclosing
  * stages' segments as a prefix (ADR 0018 §2.1). It is opaque: only ever
  * compared for exact equality (resume lookup, upsert), never parsed. A per-run
  * artifact, so the format is not a cross-version contract.
  *
  * `resultJson` is type-erased at rest — the log is heterogeneous across stage
  * types. Deserialisation back to a typed value happens at the stage call site
  * (C3), not here.
  */
case class StageEntry(id: String, name: String, resultJson: String)
    derives JsonData

/** A persisted session: the name + occurrence that key it (stage-style — see
  * [[orca.FlowControl.nextSessionOccurrence]]), a minted UUID, the seed string
  * the author supplied, and — when the session is durably resumable — the wire
  * id to resume against.
  *
  * `name` + `occurrence` default to `""` + `0` so log files written before this
  * field existed still decode — they degrade to that key and simply re-seed on
  * the next `session(...)` call. The progress log is a per-run artifact;
  * cross-version resume of an in-flight run is not supported.
  *
  * `id` is the stable client id the framework hands across calls;
  * [[SessionRecord.resumeWireId]] is the id to put on the wire when resuming
  * the live backend conversation (the same `wireId` notion as
  * [[orca.backend.Dispatch]]). Its value depends on the backend, but its
  * meaning is uniform:
  *   - codex/gemini/opencode: a backend-minted server-thread id (≠ `id`);
  *   - claude: equal to `id` itself — claude's sessions are durable on disk and
  *     its client id IS the wire id, so recording it re-claims the session
  *     (`--resume`) on a resumed run rather than re-creating it;
  *   - pi: `None` — pi's sessions live in a `deleteOnExit` temp dir, so nothing
  *     survives a restart and a resumed run always re-seeds.
  *
  * It is persisted so a resumed run can rehydrate the in-memory map and (a)
  * resume against the right wire id and (b) probe it for existence.
  *
  * `resumeWireId` defaults to `None` and decodes to `None` when absent in older
  * log files — the lenient [[ProgressLog]] codec tolerates the missing field.
  * Stored in [[ProgressLog.sessions]] so that a resumed run reuses the same
  * [[orca.agents.SessionId]] rather than minting a second one.
  *
  * `backend` records the minting agent's [[orca.agents.BackendTag]] via its
  * stable [[orca.agents.BackendTag.wireName]] (e.g. `"Codex"` — frozen
  * independently of the case name, so a future case rename can't strand this
  * field), so a resumed run's targeted rehydration
  * (`FlowLifecycle.rehydrateSessions`) knows which agent to replay this
  * record's `resumeWireId` into rather than always assuming the lead. Defaults
  * to `None` and decodes to `None` when absent in older log files (written
  * before this field existed, or before the record's agent carried a tag) — a
  * `None` record falls back to the lead, the pre-tagging behaviour. A value
  * that matches no known [[orca.agents.BackendTag.wireName]] (an edited log) is
  * skipped with a warning rather than guessed — see
  * `FlowLifecycle.targetAgent`. `agent.session(name, seed)`'s reuse arm also
  * self-heals a stale tag (a lead-backend swap between runs) rather than
  * silently re-seeding under the wrong tag forever.
  */
case class SessionRecord(
    name: String = "",
    occurrence: Int = 0,
    id: String,
    seed: String,
    resumeWireId: Option[String] = None,
    backend: Option[String] = None
) derives JsonData

/** An append-only log of stage outcomes and session records for one flow run,
  * keyed by its header.
  *
  * `sessions` defaults to `Nil` so that existing log files written before this
  * field was introduced continue to decode. The custom [[JsonData]] instance
  * below uses a lenient codec config that tolerates missing collection fields.
  */
case class ProgressLog(
    header: ProgressHeader,
    entries: List[StageEntry],
    sessions: List[SessionRecord] = Nil
)

object ProgressLog:
  /** Custom `JsonData` instance for `ProgressLog` that does **not** require the
    * `sessions` collection field to be present in the JSON. All other
    * collection fields (`entries`) are similarly lenient here — `entries` is
    * never absent in practice, but a uniform config keeps the rule simple.
    *
    * This intentionally diverges from `JsonData.strictCodecConfig`, which sets
    * `withRequireCollectionFields(true)`. That strictness is appropriate for
    * LLM-reply DTOs (where a missing list is almost certainly a model error),
    * but wrong for the progress log, which must round-trip across software
    * versions where new optional fields are added over time.
    */
  given JsonData[ProgressLog] = JsonData(
    Schema.derived[ProgressLog],
    ConfiguredJsonValueCodec.derived[ProgressLog](using
      CodecMakerConfig
        .withRequireCollectionFields(false)
        .withTransientEmpty(false)
    )
  )
