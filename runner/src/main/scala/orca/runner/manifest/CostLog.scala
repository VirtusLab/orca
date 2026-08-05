package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.events.Cost

import scala.util.control.NonFatal

/** One line of a `<id>-cost.jsonl` cost log (ADR 0021 §8 amendment,
  * 2026-08-05). Serialised with a `type` discriminator, so a reader skips a
  * record kind it doesn't know instead of failing the file — the additive rule
  * applied to the line vocabulary.
  */
private[orca] enum CostRecord:
  /** Written once, before the first turn. Repeats
    * `orcaVersion`/`flow`/`workDir` from the run's [[RunManifest]] rather than
    * referring to it: a run that spends tokens without ever committing a
    * session writes no manifest at all, and a cost log that can't be read
    * without a sibling is useless in exactly that case.
    */
  case Run(
      at: String,
      orcaVersion: String,
      flow: Option[String],
      workDir: String
  )

  /** One LLM turn, carrying everything an aggregate needs, so total, by-role,
    * by-agent and by-stage are all folds over these lines and no persisted
    * summary can disagree with them.
    *
    * `usage` covers every axis; `cost` is `None` for a model absent from the
    * pricing table, so such a turn shows tokens against no dollars. `attempt`
    * is the turn's 1-based position among the turns of its call, so retried
    * spend is separable.
    *
    * `session` is the key the conversation is recorded under in
    * [[RunManifest.sessions]], and `harness`/`sessionName` repeat what that
    * record would have said. The key alone dangles whenever the manifest was
    * never written or has since been pruned, and it is `None` for a turn that
    * arrives before its own `SessionCommitted` — which the autonomous text path
    * always does.
    */
  case Turn(
      at: String,
      agent: String,
      role: Option[String],
      stage: Option[String],
      attempt: Int,
      apiCalls: Option[Long],
      usage: ManifestUsage,
      cost: Option[Cost],
      session: Option[String],
      harness: Option[String],
      sessionName: Option[String]
  )

  /** Written by `RunManifestWriter.finish`. Its ABSENCE is the crash signal —
    * the only way a cost log says whether its run ended, since `outcome` lives
    * in the session manifest, which a turn-only run never writes.
    */
  case Finish(at: String, outcome: String)

private[orca] object CostRecord:
  given codec: ConfiguredJsonValueCodec[CostRecord] =
    ConfiguredJsonValueCodec.derived[CostRecord](using
      CodecMakerConfig.withDiscriminatorFieldName(Some("type"))
    )

/** Append-only reader/writer for one run's `<id>-cost.jsonl`.
  *
  * Append-only rather than the session manifest's whole-file rewrite: a turn
  * fires far more often than a stage or session event, and rewriting meant
  * re-serialising every turn recorded so far on each of ~40 writes. The cost is
  * that a swallowed append is that turn gone — the rewrite was self-healing,
  * this is not. Accepted: this file is measurement, and the session manifest
  * keeps its atomic rewrite.
  *
  * Not thread-safe, and doesn't need to be: the only caller is
  * [[RunManifestWriterState]], which an Ox actor serialises onto one thread.
  */
private[manifest] class CostLog(val path: os.Path):

  /** Appends one line, creating the file and its directory on first use.
    *
    * Repairs a missing trailing newline first. The realistic way this file
    * tears is not a kill — the kernel already has those bytes — but a write
    * that throws part-way through a line, which [[RunManifestWriterState]]
    * swallows so the run continues. Without the repair the next append would
    * run onto that stump and cost two records instead of one.
    */
  def append(record: CostRecord): Unit =
    os.makeDir.all(path / os.up)
    val json = writeToString(record)(using CostRecord.codec)
    os.write.append(path, s"${newlineRepair()}$json\n")

  private def newlineRepair(): String =
    if os.exists(path) && os.size(path) > 0 && lastByte() != '\n'.toByte then
      "\n"
    else ""

  private def lastByte(): Byte =
    val size = os.size(path)
    os.read.bytes(path, offset = size - 1, count = 1).head

  /** Every record in the log, in write order, skipping any line that doesn't
    * parse — a torn line costs itself and nothing after it, which is the whole
    * reason this file is line-oriented. A record kind this build doesn't know
    * is skipped the same way.
    */
  def read(): List[CostRecord] =
    if !os.exists(path) then Nil
    else
      os.read
        .lines(path)
        .iterator
        .filter(_.nonEmpty)
        .flatMap: line =>
          try Some(readFromString[CostRecord](line)(using CostRecord.codec))
          catch case NonFatal(_) => None
        .toList
