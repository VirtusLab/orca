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

  /** Lenient load for the runtime's frequent reads: every non-`Loaded` outcome
    * collapses to `None`. Use [[loadDetailed]] where the difference matters.
    */
  def load(): Option[ProgressLog]

  /** Classifies the log for callers that must act differently per outcome — the
    * lifecycle's resume decision. See [[ProgressStore.LoadResult]].
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

  /** Upsert a session record by its [[SessionKey]]: replaces an existing record
    * with that key, or appends if none exists. Last write wins.
    *
    * Requires [[writeHeader]] first; otherwise it throws. Does NOT commit — the
    * next stage commit force-adds the log and carries it. So on failure
    * teardown (`git reset --hard`) any record written since the last stage
    * commit is erased and the retry re-seeds; `session(name, seed)`'s
    * get-or-create is best-effort until a stage commit has carried the log.
    */
  def upsertSession(record: SessionRecord)(using WorkspaceWrite): Unit

  /** Record where this run published its work; last write wins.
    *
    * Requires [[writeHeader]] first; otherwise it throws. Does NOT commit — the
    * enclosing stage's commit carries it, so a failure teardown's `git reset
    * --hard` erases a record the stage never completed. The retry then
    * re-publishes, which is sound only as far as the forge call is idempotent:
    * `gh.createPr` hands back the PR that already exists as long as its
    * `findOpenPr` lookup locates it, and refuses otherwise.
    */
  def recordPublished(work: PublishedWork)(using WorkspaceWrite): Unit

object ProgressStore:

  /** Outcome of [[ProgressStore.loadDetailed]]. The two failure arms are split
    * because they call for opposite actions: content orca wrote and cannot
    * parse is safe to replace with a fresh log, while a log it could not read
    * at all may still be a resumable run, and overwriting it would destroy one.
    */
  enum LoadResult:
    /** No file at the path — the normal fresh run. */
    case Absent

    /** The file was read but does not parse: truncated, or externally edited.
      */
    case Corrupt(reason: String)

    /** The read itself failed — permissions, or a directory at the path. */
    case Unreadable(reason: String)

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

  /** Store for an already-known log path — e.g. one discovered by scanning
    * `.orca/` for `progress-*.json` files (the shell's interrupted-run
    * detection) rather than derived from a fresh prompt via [[default]].
    */
  def at(workDir: os.Path, path: os.Path): ProgressStore =
    OsProgressStore(workDir, path)

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

  // Read and parse are classified apart, since they mean different things: a
  // missing file is `Absent`, any other read failure is `Unreadable`, and only
  // content orca read but cannot parse is `Corrupt`. Absence is the read's own
  // `NoSuchFileException` rather than an `os.exists` pre-check, so there is no
  // window between the two for the file to vanish in — teardown's own
  // `os.remove` runs against this same path.
  def loadDetailed(): ProgressStore.LoadResult =
    val content =
      try Right(os.read(path))
      catch
        case _: java.nio.file.NoSuchFileException =>
          Left(ProgressStore.LoadResult.Absent)
        case NonFatal(e) =>
          Left(ProgressStore.LoadResult.Unreadable(describe(e)))
    content match
      case Left(result) => result
      case Right(text) =>
        try
          ProgressStore.LoadResult.Loaded(
            readFromString[ProgressLog](text)(using codec)
          )
        catch case NonFatal(e) => ProgressStore.LoadResult.Corrupt(describe(e))

  private def describe(e: Throwable): String =
    val firstLine =
      Option(e.getMessage).flatMap(_.linesIterator.nextOption()).getOrElse("")
    s"${e.getClass.getSimpleName}: $firstLine"

  def writeHeader(header: ProgressHeader)(using WorkspaceWrite): Unit =
    writeLog(ProgressLog(header, Nil))

  def appendEntry(entry: StageEntry)(using WorkspaceWrite): Unit =
    writeLog(upsertEntry(currentLogOrThrow("appendEntry"), entry))

  def upsertSession(record: SessionRecord)(using WorkspaceWrite): Unit =
    writeLog(upsertSessionRecord(currentLogOrThrow("upsertSession"), record))

  def recordPublished(work: PublishedWork)(using WorkspaceWrite): Unit =
    writeLog(currentLogOrThrow("recordPublished").copy(published = Some(work)))

  /** Read-modify-write precondition for [[appendEntry]] / [[upsertSession]] /
    * [[recordPublished]]: all require a log to already exist. Routed through
    * [[loadDetailed]] so an `Absent` log (writeHeader never ran), a `Corrupt`
    * one (a torn write or external edit mid-run) and an `Unreadable` one get
    * distinct messages.
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
      case ProgressStore.LoadResult.Unreadable(reason) =>
        throw IllegalStateException(
          s"$callerName could not read the log at $path: $reason"
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
    val idx = log.sessions.indexWhere(_.key == record.key)
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
