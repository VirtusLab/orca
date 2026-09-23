package orca.progress

import orca.{OrcaDir, RunKey, WorkspaceWrite}
import orca.util.JsonFile

/** Persistent store for a single flow run's [[ProgressLog]]. One
  * implementation, [[ProgressStore.default]]; the trait lets tests inject a
  * failing store.
  *
  * Mutations are gated on [[WorkspaceWrite]] to mark them as index-like,
  * fork-opaque writes (ADR 0018 §6); a write off the token's thread throws.
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
    * lifecycle's resume decision. See [[JsonFile.Read]].
    */
  def loadDetailed(): JsonFile.Read[ProgressLog]

  /** Classify the log's current content, keeping its bytes for
    * [[restoreIfRemoved]]. `Left` is why a present file could not be read.
    */
  private[orca] def peek(): Either[String, PeekedLog]

  /** Write back `peeked`'s bytes if the file was removed after the peek (a
    * stash removes an untracked log). No-op otherwise.
    */
  private[orca] def restoreIfRemoved(peeked: PeekedLog)(using
      WorkspaceWrite
  ): Unit

  /** Delete the file; a missing one is not an error. */
  private[orca] def remove()(using WorkspaceWrite): Unit

  def writeHeader(header: ProgressHeader)(using WorkspaceWrite): Unit

  /** Upsert an entry by id: replaces an existing entry with the same id
    * in-place, or appends if no entry with that id exists. Last write wins.
    *
    * Requires [[writeHeader]] to have been called first (a log must already
    * exist); otherwise it throws.
    */
  def upsertEntry(entry: StageEntry)(using WorkspaceWrite): Unit

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

  /** Default OS-backed store: JSON at
    * `<workDir>/.orca/runs/<key>.progress.json`. Two unrelated prompts in the
    * same repo produce different files; rerunning the same prompt resumes the
    * same log.
    */
  def default(workDir: os.Path, key: RunKey): ProgressStore =
    OsProgressStore(workDir, OrcaDir.progressPath(workDir, key))

private class OsProgressStore(workDir: os.Path, val path: os.Path)
    extends ProgressStore:

  def load(): Option[ProgressLog] =
    loadDetailed() match
      case JsonFile.Read.Loaded(log) => Some(log)
      case _                         => None

  def loadDetailed(): JsonFile.Read[ProgressLog] = JsonFile.read(path)

  private[orca] def peek(): Either[String, PeekedLog] =
    JsonFile
      .readBytes(path)
      .map:
        case None => PeekedLog.Absent
        case Some(bytes) =>
          JsonFile.decode[ProgressLog](bytes) match
            case Right(_) => PeekedLog.Parseable(bytes)
            case Left(_)  => PeekedLog.Unparseable(bytes)

  private[orca] def restoreIfRemoved(peeked: PeekedLog)(using
      ws: WorkspaceWrite
  ): Unit =
    ws.check("progressStore.restoreIfRemoved")
    peeked match
      case PeekedLog.Absent => ()
      case PeekedLog.Parseable(bytes) =>
        restoreBytesIfRemoved(bytes)
      case PeekedLog.Unparseable(bytes) =>
        restoreBytesIfRemoved(bytes)

  private def restoreBytesIfRemoved(bytes: IArray[Byte]): Unit =
    if !os.exists(path) then
      val _ = OrcaDir.ensureRuns(workDir)
      os.write(path, IArray.genericWrapArray(bytes).toArray)

  private[orca] def remove()(using ws: WorkspaceWrite): Unit =
    ws.check("progressStore.remove")
    val _ = os.remove(path)

  def writeHeader(header: ProgressHeader)(using ws: WorkspaceWrite): Unit =
    ws.check("progressStore.writeHeader")
    writeLog(ProgressLog(header, Nil, None))

  def upsertEntry(entry: StageEntry)(using ws: WorkspaceWrite): Unit =
    ws.check("progressStore.upsertEntry")
    writeLog(withEntry(currentLogOrThrow("upsertEntry"), entry))

  def recordPublished(work: PublishedWork)(using ws: WorkspaceWrite): Unit =
    ws.check("progressStore.recordPublished")
    writeLog(currentLogOrThrow("recordPublished").copy(published = Some(work)))

  /** Read-modify-write precondition for [[upsertEntry]] and
    * [[recordPublished]]: both require a log to already exist. Routed through
    * [[loadDetailed]] so an `Absent` log (writeHeader never ran), a `Corrupt`
    * one (a torn write or external edit mid-run) and an `Unreadable` one get
    * distinct messages.
    */
  private def currentLogOrThrow(callerName: String): ProgressLog =
    loadDetailed() match
      case JsonFile.Read.Loaded(log) => log
      case JsonFile.Read.Absent =>
        throw IllegalStateException(
          s"$callerName called before writeHeader: no log at $path"
        )
      case JsonFile.Read.Corrupt(reason) =>
        throw IllegalStateException(
          s"$callerName found a corrupted log at $path: $reason"
        )
      case JsonFile.Read.Unreadable(reason) =>
        throw IllegalStateException(
          s"$callerName could not read the log at $path: $reason"
        )

  private def withEntry(log: ProgressLog, entry: StageEntry): ProgressLog =
    val idx = log.entries.indexWhere(_.id == entry.id)
    val updated =
      if idx >= 0 then log.entries.updated(idx, entry)
      else log.entries :+ entry
    log.copy(entries = updated)

  // Rewrite the whole file each time rather than append JSONL: the log is a
  // single structured document whose entries `withEntry` replaces in place,
  // which an append-only log can't express, and it's small and bounded so a
  // full rewrite is negligible. The temp file is staged under the self-ignored
  // cache, not beside the log: a kill between temp and rename must not leave a
  // stray file in committed `.orca/` for the next stage's `git add -A` (or a
  // dirty-tree stash) to pick up.
  private def writeLog(log: ProgressLog): Unit =
    val _ = OrcaDir.ensureRuns(workDir)
    JsonFile.write(path, OrcaDir.ensureCache(workDir), log)
