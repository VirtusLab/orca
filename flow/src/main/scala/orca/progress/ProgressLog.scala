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
  * `resultJson` is type-erased at rest — the log is heterogeneous across stage
  * types. Deserialisation back to a typed value happens at the stage call site
  * (C3), not here.
  */
case class StageEntry(id: String, name: String, resultJson: String)
    derives JsonData

/** A persisted session: the occurrence index, a minted UUID, the seed string
  * the author supplied, and — for server-id backends (codex/gemini/opencode) —
  * the learned server-side session id.
  *
  * `id` is the stable client id the framework hands across calls;
  * [[SessionRecord.serverId]] is the backend-minted id the client maps to (for
  * claude/pi the client id IS the wire id, so `serverId` stays `None`). It is
  * persisted so a resumed run can rehydrate the in-memory client→server map and
  * (a) resume the right server thread and (b) probe the server id for
  * existence.
  *
  * `serverId` defaults to `None` and decodes to `None` when absent in older log
  * files — the lenient [[ProgressLog]] codec tolerates the missing field.
  * Stored in [[ProgressLog.sessions]] so that a resumed run reuses the same
  * [[orca.agents.SessionId]] rather than minting a second one.
  */
case class SessionRecord(
    index: Int,
    id: String,
    seed: String,
    serverId: Option[String] = None
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
