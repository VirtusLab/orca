package orca.sessions

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.{OrcaDir, StagePath, WorkspaceWrite}
import orca.agents.SessionKey
import orca.progress.ProgressStore
import orca.util.AtomicFile

import scala.util.control.NonFatal

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
  * `backend` records the minting agent's [[orca.agents.BackendTag]] via its
  * stable [[orca.agents.BackendTag.wireName]] (frozen independently of the case
  * name), so targeted rehydration (`FlowLifecycle.rehydrateSessions`) knows
  * which agent to replay `resumeWireId` into rather than assuming the lead.
  * `None` when the minting agent carries no backend tag (a stub agent) — falls
  * back to the lead. A value matching no known `wireName` (an edited file) is
  * skipped with a warning rather than guessed (`FlowLifecycle.targetAgent`);
  * `agent.session(name, seed)`'s reuse arm self-heals a stale tag from a
  * lead-backend swap.
  *
  * The two `Option` fields carry Scala defaults under the tolerant-decoding
  * exception AGENTS.md grants this shape: these records are live local state an
  * in-flight run must still read after an orca upgrade.
  */
case class SessionRecord(
    name: String,
    stage: String,
    id: String,
    seed: String,
    resumeWireId: Option[String] = None,
    backend: Option[String] = None
):
  /** The key this record is stored under. The single place a persisted stage id
    * is read back into a [[StagePath]].
    */
  def key: SessionKey =
    SessionKey(name = name, stage = StagePath.fromValue(stage))

/** The durable-session records of one run, in machine-local cache rather than
  * in the committed progress log.
  *
  * A backend session id is a handle into the coding agent's own store on this
  * machine: it means nothing in another checkout or on another machine, so it
  * is not history the feature branch should carry. Living in `.orca/cache/`
  * also makes it survive everything that erases uncommitted work — the failure
  * teardown's `git reset --hard`, its `git clean -fd`, and the resume-time
  * stash of a dirty tree — which is what makes `agent.session(name, seed)`'s
  * reuse branch reachable at all: a stage that fails is exactly the stage a
  * resume re-runs, and re-running it must land back on the conversation the
  * first attempt started.
  *
  * Losing the file is not a failure mode, only a cost: a run that finds no
  * record at a key mints a fresh session and primes it from the seed, the same
  * uniform fallback as a backend conversation the probe reports gone.
  *
  * Writes take [[WorkspaceWrite]] like the progress log's: this is a
  * read-modify-write of one shared file, so two forks writing it concurrently
  * would drop a record (ADR 0018 §6). The token is not inspected at runtime —
  * the type is the guard.
  */
trait SessionStore:
  /** The on-disk path of this store's JSON file. */
  def path: os.Path

  /** Every record this run has minted, in mint order. Empty when the file is
    * absent, unreadable, or does not parse.
    */
  def records(): List[SessionRecord]

  /** Upsert `record` by its [[SessionKey]]: replaces an existing record with
    * that key, or appends if none exists. Last write wins. Creates the file if
    * it is not there yet.
    */
  def upsert(record: SessionRecord)(using WorkspaceWrite): Unit

  /** Drop the file. Called by the successful teardown that also removes the
    * progress log, so a later run of the same prompt starts a fresh
    * conversation instead of continuing a finished run's.
    */
  def discard()(using WorkspaceWrite): Unit

object SessionStore:
  /** Default OS-backed store: JSON at
    * `<workDir>/.orca/cache/sessions-<promptHash>.json`, under the same prompt
    * hash as [[ProgressStore.default]]'s log. A resumed run is the same prompt
    * in the same working directory — including a `--worktree` run, which does
    * all of this inside the worktree — so it derives the same path and reads
    * back its own records.
    */
  def default(workDir: os.Path, userPrompt: String): SessionStore =
    OsSessionStore(
      workDir,
      OrcaDir.sessionRecordsPath(workDir, ProgressStore.hashPrompt(userPrompt))
    )

private class OsSessionStore(workDir: os.Path, val path: os.Path)
    extends SessionStore:

  def records(): List[SessionRecord] =
    try readFromString(os.read(path))(using OsSessionStore.codec)
    catch case NonFatal(_) => Nil

  def upsert(record: SessionRecord)(using WorkspaceWrite): Unit =
    val current = records()
    val idx = current.indexWhere(_.key == record.key)
    val updated =
      if idx >= 0 then current.updated(idx, record) else current :+ record
    AtomicFile.write(
      path,
      OrcaDir.ensureCache(workDir),
      writeToString(updated)(using OsSessionStore.codec)
    )

  def discard()(using WorkspaceWrite): Unit =
    try os.remove(path): Unit
    catch case _: java.nio.file.NoSuchFileException => ()

private object OsSessionStore:
  // `withTransientNone(false)` keeps `resumeWireId`/`backend` as explicit
  // `null`s: the file is read by a person debugging a resume, and a key that
  // vanishes when unset reads as a different shape each run.
  val codec: JsonValueCodec[List[SessionRecord]] =
    ConfiguredJsonValueCodec.derived[List[SessionRecord]](using
      CodecMakerConfig.withTransientNone(false)
    )
