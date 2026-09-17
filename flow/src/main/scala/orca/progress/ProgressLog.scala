package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.agents.{JsonData, given}
import orca.util.RawJson
import sttp.tapir.Schema

/** Whether orca minted [[ProgressHeader.branch]] itself or bound to a
  * pre-existing one. Gates the throwaway-branch auto-delete
  * (`FlowLifecycle.finishBranch`): a branch orca never created must never be
  * deleted, even if a tampered header's `startingBranch` is crafted to make it
  * look throwaway.
  */
enum BranchMode derives JsonData:
  /** Orca minted `branch` itself — the ordinary case. */
  case Created

  /** Orca bound to a pre-existing branch without creating it (skip-branch mode,
    * ADR 0018 amendment).
    */
  case Reused

/** Header capturing the git context in which the progress log was started.
  *
  * `userPrompt`/`flowName` back the shell's "Resume interrupted run" offer (ADR
  * 0021 §3 amendment): the log otherwise records only `promptHash`, not the
  * prompt text itself, so a byte-identical re-run needs it spelled out here.
  * Both are `Option` with a `None` default under the SAME tolerant- decoding
  * exception as [[SessionRecord]]'s own optional fields (see [[ProgressLog]]'s
  * codec) — a log written before this field existed decodes with both `None`,
  * and the resume offer simply doesn't apply to it (an old in-flight run across
  * an orca upgrade still resumes; it just isn't one-keystroke). `flowName` is
  * separately `None` for a run started outside the shell (no `ORCA_FLOW_NAME`
  * to record) even on a freshly written header.
  *
  * `startingCommit` is the commit HEAD pointed at when the run bound its branch
  * — the diff base for a review of everything the whole run changed. It carries
  * the same `Option`/`None` tolerant-decoding exception, and unlike the fields
  * above it stays lenient on load: a header missing it, or carrying something
  * that isn't a commit hash, reads as absent rather than aborting the run
  * ([[RecoveryCheck.startingCommit]]). A fresh run always writes `Some`.
  */
case class ProgressHeader(
    startingBranch: String,
    branch: String,
    promptHash: String,
    branchMode: BranchMode,
    userPrompt: Option[String] = None,
    flowName: Option[String] = None,
    startingCommit: Option[String] = None
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

/** The pair that identifies a durable session: the `name` it was minted under
  * (its role, e.g. `implementer`) and the `detail` telling it apart from the
  * other sessions sharing that name — typically the task it serves. Free text,
  * compared by exact string equality, and never used as a filename or a wire
  * token, so it needs no escaping. Empty for a session that is the only one
  * under its name.
  */
case class SessionKey(name: String, detail: String):
  /** How the key reads in user-facing text: the name alone for a session with
    * no detail, `name (detail)` otherwise.
    */
  def label: String = if detail.isEmpty then name else s"$name ($detail)"

/** A persisted session: the [[SessionKey]] fields that key it, a minted UUID,
  * the seed string the author supplied, and — when the session is durably
  * resumable — the wire id to resume against.
  *
  * `id` is the stable client id the framework hands across calls;
  * [[SessionRecord.resumeWireId]] is the id to put on the wire when resuming
  * the live backend conversation (same `wireId` notion as
  * [[orca.backend.Dispatch]]). Its value depends on the backend:
  *   - codex/gemini/opencode: a backend-minted server-thread id (≠ `id`);
  *   - claude/pi: equal to `id` itself — both claim the id client-side and keep
  *     a durable transcript, so recording it re-claims the session (`--resume`
  *     / `--continue`) on a resumed run.
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
  * `agent.session(name, detail, seed)`'s reuse arm self-heals a stale tag from
  * a lead-backend swap.
  */
case class SessionRecord(
    name: String,
    detail: String,
    id: String,
    seed: String,
    resumeWireId: Option[String] = None,
    backend: Option[String] = None
) derives JsonData:
  def key: SessionKey = SessionKey(name, detail)

/** One flow run's persisted state, keyed by its header: the outcome of each
  * completed stage, the sessions it minted, and where it published its work.
  * The custom [[JsonData]] instance below tolerates missing collection fields
  * so logs round-trip across software versions.
  *
  * `published` is [[PublishedWork]] for a run that published. Its `None`
  * default falls under the same tolerant-decoding exception as
  * [[ProgressHeader]]'s optional fields.
  */
case class ProgressLog(
    header: ProgressHeader,
    entries: List[StageEntry],
    sessions: List[SessionRecord] = Nil,
    published: Option[PublishedWork] = None
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
