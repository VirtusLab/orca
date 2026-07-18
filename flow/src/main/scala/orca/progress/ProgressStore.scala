package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.{OrcaDir, WorkspaceWrite}
import orca.agents.JsonData
import scala.util.control.NonFatal

/** Persistent store for a single flow run's [[ProgressLog]].
  *
  * Mutations are gated on [[WorkspaceWrite]] to mark them as index-like,
  * fork-opaque writes (ADR 0018 §6). The token is not inspected at runtime —
  * the type is the guard.
  */
trait ProgressStore:
  /** The on-disk path of this store's JSON file. The stage commit force-adds
    * this single path so the log is committed even when `.orca/` is gitignored.
    */
  def path: os.Path

  /** Lenient load for the runtime's frequent reads: absent and corrupt both
    * collapse to `None`. Use [[loadDetailed]] where the difference matters.
    */
  def load(): Option[ProgressLog]

  /** Distinguishes an absent log (normal fresh run) from a present-but-
    * unparseable one (corrupt or truncated — the caller starts fresh but WARNS,
    * since the user may have expected a resume). Used by the lifecycle's resume
    * decision.
    */
  def loadDetailed(): ProgressStore.LoadResult

  def writeHeader(header: ProgressHeader)(using WorkspaceWrite): Unit

  /** Upsert an entry by id: replaces an existing entry with the same id
    * in-place, or appends if no entry with that id exists. Last write wins.
    *
    * Requires [[writeHeader]] to have been called first (a log must already
    * exist); otherwise it throws.
    */
  def appendEntry(entry: StageEntry)(using WorkspaceWrite): Unit

  /** Upsert a session record by [[SessionRecord.name]] +
    * [[SessionRecord.occurrence]]: replaces an existing record with that key,
    * or appends if none exists. Last write wins.
    *
    * Requires [[writeHeader]] first; otherwise it throws. Does NOT commit — the
    * next stage commit force-adds the log and carries it. So on failure
    * teardown (`git reset --hard`) any record written since the last stage
    * commit is erased and the retry re-seeds; `session(name, seed)`'s
    * get-or-create is best-effort until a stage commit has carried the log.
    */
  def upsertSession(record: SessionRecord)(using WorkspaceWrite): Unit

object ProgressStore:

  /** Outcome of [[ProgressStore.loadDetailed]]. */
  enum LoadResult:
    case Absent
    case Corrupt(reason: String)
    case Loaded(log: ProgressLog)

  /** Default OS-backed store: JSON at `<workDir>/.orca/progress-<hash>.json`.
    *
    * `hash` is the first 12 hex chars of SHA-256(userPrompt). Two unrelated
    * prompts in the same repo produce different files; rerunning the same
    * prompt resumes the same log.
    */
  def default(workDir: os.Path, userPrompt: String): ProgressStore =
    OsProgressStore(
      workDir,
      OrcaDir.progressPath(workDir, hashPrompt(userPrompt))
    )

  /** First 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars.
    * Package-private so the flow lifecycle can stamp the same hash into the
    * progress header (ADR 0018 §2.4).
    */
  private[orca] def hashPrompt(userPrompt: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(userPrompt.getBytes("UTF-8"))
    digest.iterator.take(6).map(b => f"${b & 0xff}%02x").mkString

private class OsProgressStore(workDir: os.Path, val path: os.Path)
    extends ProgressStore:

  private val codec = summon[JsonData[ProgressLog]].codec

  def load(): Option[ProgressLog] =
    loadDetailed() match
      case ProgressStore.LoadResult.Loaded(log) => Some(log)
      case _                                    => None

  def loadDetailed(): ProgressStore.LoadResult =
    if !os.exists(path) then ProgressStore.LoadResult.Absent
    else parseLog(os.read(path))

  def writeHeader(header: ProgressHeader)(using WorkspaceWrite): Unit =
    writeLog(ProgressLog(header, Nil))

  def appendEntry(entry: StageEntry)(using WorkspaceWrite): Unit =
    writeLog(upsertEntry(currentLogOrThrow("appendEntry"), entry))

  def upsertSession(record: SessionRecord)(using WorkspaceWrite): Unit =
    writeLog(upsertSessionRecord(currentLogOrThrow("upsertSession"), record))

  /** Read-modify-write precondition for [[appendEntry]] / [[upsertSession]]:
    * both require a log to already exist. Routed through [[loadDetailed]] so an
    * `Absent` log (writeHeader never ran) and a `Corrupt` one (a torn write or
    * external edit mid-run) get distinct messages.
    */
  private def currentLogOrThrow(callerName: String): ProgressLog =
    loadDetailed() match
      case ProgressStore.LoadResult.Loaded(log) => log
      case ProgressStore.LoadResult.Absent =>
        throw IllegalStateException(
          s"$callerName called before writeHeader: no log at $path"
        )
      case ProgressStore.LoadResult.Corrupt(reason) =>
        throw IllegalStateException(
          s"$callerName found a corrupted log at $path: $reason"
        )

  private def upsertEntry(log: ProgressLog, entry: StageEntry): ProgressLog =
    val idx = log.entries.indexWhere(_.id == entry.id)
    val updated =
      if idx >= 0 then log.entries.updated(idx, entry)
      else log.entries :+ entry
    log.copy(entries = updated)

  private def upsertSessionRecord(
      log: ProgressLog,
      record: SessionRecord
  ): ProgressLog =
    val idx = log.sessions.indexWhere(r =>
      r.name == record.name && r.occurrence == record.occurrence
    )
    val updated =
      if idx >= 0 then log.sessions.updated(idx, record)
      else log.sessions :+ record
    log.copy(sessions = updated)

  // Rewrite the whole file each time rather than append JSONL: the log is a
  // single structured document whose elements `upsertEntry`/`upsertSession`
  // mutate in place, which an append-only log can't express, and it's small and
  // bounded so a full rewrite is negligible.
  //
  // Written atomically via a sibling temp file + `os.move(atomicMove = true)`:
  // a plain `os.write.over` can tear the file if the process dies mid-write,
  // leaving `loadDetailed()` reading `Corrupt` where a resume was expected.
  private def writeLog(log: ProgressLog): Unit =
    val dir = OrcaDir.ensureRoot(workDir)
    val tmp = os.temp(
      contents = writeToString(log)(using codec),
      dir = dir,
      prefix = s".${path.last}.",
      suffix = ".tmp",
      deleteOnExit = false
    )
    // Wrapped so ANY failure cleans up the temp file rather than leaking it;
    // the target is untouched on failure.
    try
      try os.move(tmp, path, replaceExisting = true, atomicMove = true)
      catch
        // Some filesystems (network mounts, some container overlay/bind mounts)
        // reject ATOMIC_MOVE even for a same-directory rename. Torn writes are
        // impossible there anyway (only the atomicity guarantee against
        // concurrent readers is unavailable), so a plain move is a safe
        // fallback.
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          os.move(tmp, path, replaceExisting = true)
    catch
      case NonFatal(e) =>
        if os.exists(tmp) then os.remove(tmp): Unit
        throw e

  private def parseLog(json: String): ProgressStore.LoadResult =
    try
      ProgressStore.LoadResult.Loaded(
        readFromString[ProgressLog](json)(using codec)
      )
    catch
      case NonFatal(e) =>
        val firstLine =
          Option(e.getMessage)
            .flatMap(_.linesIterator.nextOption())
            .getOrElse("")
        ProgressStore.LoadResult.Corrupt(
          s"${e.getClass.getSimpleName}: $firstLine"
        )
