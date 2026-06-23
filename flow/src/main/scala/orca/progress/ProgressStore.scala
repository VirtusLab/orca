package orca.progress

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.{InStage}
import orca.llm.JsonData
import scala.util.control.NonFatal

/** Persistent store for a single flow run's [[ProgressLog]].
  *
  * Mutations are gated on [[InStage]] to mark them as working-tree-mutating
  * operations (ADR 0018 §2.2). The token is not inspected at runtime — the type
  * is the guard.
  */
trait ProgressStore:
  /** The on-disk path of this store's JSON file. The stage commit force-adds
    * this single path so the log is committed even when `.orca/` is gitignored.
    */
  def path: os.Path

  def load(): Option[ProgressLog]
  def writeHeader(header: ProgressHeader)(using InStage): Unit

  /** Upsert an entry by id: replaces an existing entry with the same id
    * in-place, or appends if no entry with that id exists. Last write wins.
    */
  def appendEntry(entry: StageEntry)(using InStage): Unit

object ProgressStore:

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
    if !os.exists(path) then None
    else parseLog(os.read(path))

  def writeHeader(header: ProgressHeader)(using InStage): Unit =
    writeLog(ProgressLog(header, Nil))

  def appendEntry(entry: StageEntry)(using InStage): Unit =
    val current = load().getOrElse(
      throw IllegalStateException(
        s"appendEntry called before writeHeader: no log at $path"
      )
    )
    writeLog(upsert(current, entry))

  private def upsert(log: ProgressLog, entry: StageEntry): ProgressLog =
    val idx = log.entries.indexWhere(_.id == entry.id)
    val updated =
      if idx >= 0 then log.entries.updated(idx, entry)
      else log.entries :+ entry
    log.copy(entries = updated)

  private def writeLog(log: ProgressLog): Unit =
    os.write.over(path, writeToString(log)(using codec), createFolders = true)

  private def parseLog(json: String): Option[ProgressLog] =
    try Some(readFromString[ProgressLog](json)(using codec))
    catch case NonFatal(_) => None
