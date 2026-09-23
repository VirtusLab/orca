package orca.sessions

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.{OrcaDir, OrcaFlowException, RunKey, WorkspaceWrite}
import orca.util.JsonFile

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

  /** Upsert `record` by its [[orca.agents.SessionKey]]: replaces an existing
    * record with that key, or appends if none exists. Last write wins. Creates
    * the file if it is not there yet.
    */
  def upsert(record: SessionRecord)(using WorkspaceWrite): Unit

  /** Drop the file. Called by the successful teardown that also removes the
    * progress log, so a later run of the same prompt starts a fresh
    * conversation instead of continuing a finished run's.
    */
  def discard()(using WorkspaceWrite): Unit

object SessionStore:
  /** Default OS-backed store: JSON at
    * `<workDir>/.orca/cache/runs/<key>.sessions.json`, under the same
    * [[RunKey]] as `ProgressStore.default`'s log. A resumed run is the same
    * prompt in the same working directory — including a `--worktree` run, which
    * does all of this inside the worktree — so it derives the same path and
    * reads back its own records.
    */
  def default(workDir: os.Path, key: RunKey): SessionStore =
    OsSessionStore(workDir, OrcaDir.sessionRecordsPath(workDir, key))

private class OsSessionStore(workDir: os.Path, val path: os.Path)
    extends SessionStore:

  private given JsonValueCodec[List[SessionRecord]] = OsSessionStore.codec

  def records(): List[SessionRecord] =
    JsonFile.read[List[SessionRecord]](path) match
      case JsonFile.Read.Loaded(records) => records
      case _                             => Nil

  // Unlike `records()`, this read-modify-write refuses an unreadable file:
  // renaming one record over it would destroy whatever it still holds.
  def upsert(record: SessionRecord)(using WorkspaceWrite): Unit =
    val current = JsonFile.read[List[SessionRecord]](path) match
      case JsonFile.Read.Loaded(records)                   => records
      case JsonFile.Read.Absent | JsonFile.Read.Corrupt(_) => Nil
      case JsonFile.Read.Unreadable(reason) =>
        throw new OrcaFlowException(
          s"session records at $path exist but cannot be read ($reason)"
        )
    val idx = current.indexWhere(_.key == record.key)
    val updated =
      if idx >= 0 then current.updated(idx, record) else current :+ record
    JsonFile.write(path, OrcaDir.ensureCacheRuns(workDir), updated)

  def discard()(using WorkspaceWrite): Unit = os.remove(path): Unit

private object OsSessionStore:
  // `transientNone` off, so `resumeWireId`/`backend` are written as explicit
  // `null`s while unset: the file is read by a person debugging a resume, and
  // a key that vanishes reads as a different shape each run.
  val codec: JsonValueCodec[List[SessionRecord]] =
    ConfiguredJsonValueCodec.derived[List[SessionRecord]](using
      CodecMakerConfig.withTransientNone(false)
    )
