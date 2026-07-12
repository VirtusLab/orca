package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.WorkspaceWrite
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

  /** Lenient load for the runtime's frequent reads (rehydration, `appendEntry`
    * / `upsertSession`'s read-modify-write): absent and corrupt both collapse
    * to `None`, since those call sites don't need to tell the two apart.
    * `loadDetailed()` is the one call site that does — the lifecycle's resume
    * decision.
    */
  def load(): Option[ProgressLog]

  /** Detailed load for the lifecycle's resume decision: distinguishes an absent
    * log (normal fresh run) from a present-but-unparseable one (a corrupt or
    * truncated file — the caller starts fresh but WARNS, because the user may
    * have expected a resume). `load()` keeps its lenient Option shape for the
    * runtime's frequent reads.
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
    * Requires [[writeHeader]] to have been called first; otherwise it throws.
    * Does NOT commit — the session is recorded in the log file only; the next
    * stage commit will force-add the log and carry it. Consequence: on failure
    * teardown (`git reset --hard`) any session record written since the last
    * stage commit is erased — the retry then mints a fresh session and
    * re-seeds. This is the intended fail-safe; `session(name, seed)`'s
    * get-or-create contract is therefore best-effort until a stage commit has
    * carried the log.
    */
  def upsertSession(record: SessionRecord)(using WorkspaceWrite): Unit

object ProgressStore:

  /** Outcome of [[ProgressStore.loadDetailed]]. Nested here (rather than a
    * top-level type) so it reads as `ProgressStore.LoadResult` at import sites
    * — it's meaningless without the store it came from, same call as
    * `Verdict.RejectionKind`.
    */
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
      workDir / os.sub / ".orca" / s"progress-${hashPrompt(userPrompt)}.json"
    )

  /** First 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars.
    * Package-private so the flow lifecycle can stamp the same hash into the
    * progress header (ADR 0018 §2.4).
    */
  private[orca] def hashPrompt(userPrompt: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(userPrompt.getBytes("UTF-8"))
    digest.iterator.take(6).map(b => f"${b & 0xff}%02x").mkString

private class OsProgressStore(val path: os.Path) extends ProgressStore:

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
    * both require a log to already exist (`writeHeader` must have run first).
    * Routed through [[loadDetailed]] (not the lenient [[load]]) so the two ways
    * "no usable log" can happen get distinct, honest messages: a log that was
    * genuinely never written (`Absent` — the real protocol violation the
    * original message described) vs. one that exists but is corrupted
    * (`Corrupt` — a torn write, an external edit — mid-run, which `load()`'s
    * collapsing to `None` used to misreport as "before writeHeader" even though
    * writeHeader plainly *did* run).
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

  // We rewrite the whole file each time rather than append JSONL: the log is a
  // single structured *document* (a header + entries + sessions object), not a
  // flat event stream — `upsertEntry`/`upsertSession` mutate existing elements,
  // which an append-only log can't express. It's also small and bounded (a
  // handful of stages + sessions per run), so a full rewrite is negligible.
  //
  // Written atomically: a plain `os.write.over` can tear the file if the
  // process dies mid-write (a killed run, an OOM), leaving `loadDetailed()`
  // reading `Corrupt` where a resume was expected. Writing to a sibling temp
  // file (same directory, so the same filesystem) then `os.move` with
  // `atomicMove = true` makes the visible file always either the old complete
  // content or the new complete content, never a partial write.
  private def writeLog(log: ProgressLog): Unit =
    val dir = path / os.up
    os.makeDir.all(dir)
    val tmp = os.temp(
      contents = writeToString(log)(using codec),
      dir = dir,
      prefix = s".${path.last}.",
      suffix = ".tmp",
      deleteOnExit = false
    )
    // The whole move sequence (including the fallback) is wrapped so that ANY
    // failure — not just the handled AtomicMoveNotSupportedException — cleans
    // up the temp file instead of leaking it. The target itself is untouched
    // on failure; only the orphaned `tmp` is removed here.
    try
      try os.move(tmp, path, replaceExisting = true, atomicMove = true)
      catch
        // Some filesystems (network mounts, certain container overlay/bind
        // mounts) reject ATOMIC_MOVE even for a same-directory rename,
        // throwing this checked exception. Torn writes are impossible on
        // those anyway (rename semantics still apply — only the *atomicity
        // guarantee against concurrent readers* is unavailable) so a plain
        // move is a safe fallback, not a silent downgrade of the actual
        // protection we need.
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
