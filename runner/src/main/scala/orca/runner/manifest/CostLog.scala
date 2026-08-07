package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.events.{Cost, Usage}

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** The persisted projection of [[orca.events.Usage]]'s token axes.
  *
  * The field names are `Usage`'s and so are the JSON keys: every axis persisted
  * here has to be traceable to the one it mirrors, or the two drift and a
  * reader silently reports the wrong money. `RunManifestTest` pins that
  * correspondence against `Usage`'s own field list, so an axis added there
  * fails a test instead of silently vanishing from the log.
  *
  * `inputTokens` is the one name that isn't a field of `Usage`: it is the TOTAL
  * prompt, `cacheReadInputTokens` and `cacheWriteInputTokens` INCLUDED, so
  * adding the three double counts. `apiCalls`, an axis of `Usage`, isn't here
  * at all: it lives on [[CostRecord.Turn]], beside the attribution fields.
  *
  * Deliberately carries no money, unlike `Usage`: `Usage.cost` is only the
  * portion backends reported, and an unlabelled figure next to a resolved
  * [[orca.events.Cost]] is how reported and estimated spend get mixed.
  */
private[orca] case class ManifestUsage(
    inputTokens: Long,
    outputTokens: Long,
    cacheReadInputTokens: Long,
    cacheWriteInputTokens: Long,
    reasoningOutputTokens: Long
)

private[orca] object ManifestUsage:
  def of(usage: Usage): ManifestUsage = ManifestUsage(
    inputTokens = usage.inputTokens,
    outputTokens = usage.outputTokens,
    cacheReadInputTokens = usage.cacheReadInputTokens,
    cacheWriteInputTokens = usage.cacheWriteInputTokens,
    reasoningOutputTokens = usage.reasoningOutputTokens
  )

/** One line of a `<id>-cost.jsonl` cost log (ADR 0021 §8 amendment,
  * 2026-08-05). The `type` discriminator lets a reader skip a record kind it
  * doesn't know rather than fail the file.
  */
private[orca] enum CostRecord:
  /** Written once, before the first turn. `orcaVersion` and `flow` have nowhere
    * else to live for a run that spends tokens without ever committing a
    * session, since such a run writes no [[RunManifest]] at all. `workDir` is
    * recoverable from the file's own path; repeated so the log is
    * self-contained when copied.
    */
  case Run(orcaVersion: String, flow: Option[String], workDir: String)

  /** One LLM turn, carrying every axis an aggregate needs: total, by-role,
    * by-agent and by-stage are all folds over these lines, so an axis missing
    * here cannot be recovered.
    *
    * `cost` is `None` for a model absent from the pricing table, so such a turn
    * shows tokens against no dollars. `attempt` is the turn's 1-based position
    * among the turns of its call, so retried spend is separable. `session` is
    * the key the conversation is recorded under in [[RunManifest.sessions]].
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
      session: Option[String]
  )

  /** Written by `RunManifestWriter.finish`. Distinguishes a succeeded run from
    * a failed one for a turn-only run, whose `outcome` has no other home — a
    * distinction that changes how the run's spend reads.
    */
  case Finish(at: String, outcome: String)

private[orca] object CostRecord:
  given codec: ConfiguredJsonValueCodec[CostRecord] =
    ConfiguredJsonValueCodec.derived[CostRecord](using
      CodecMakerConfig.withDiscriminatorFieldName(Some("type"))
    )

/** Append-only reader/writer for one run's `<id>-cost.jsonl`.
  *
  * Appending, unlike the session manifest's whole-file rewrite, is not
  * self-healing: a swallowed append is that turn gone for good. Accepted — this
  * file is measurement, and turns are frequent enough that rewriting would
  * re-serialise the whole log on every write.
  *
  * Not thread-safe, and doesn't need to be: the only caller is
  * [[RunManifestWriterState]], which an Ox actor serialises onto one thread.
  */
private[manifest] class CostLog(val path: os.Path):

  def append(record: CostRecord): Unit =
    os.makeDir.all(path / os.up)
    os.write.append(path, s"${writeToString(record)(using CostRecord.codec)}\n")

  /** Every record in the log, in write order, skipping any line that doesn't
    * parse — which covers both a line torn by a failed write and a record kind
    * this build doesn't know.
    *
    * Decodes the whole file through `String`'s replacing decoder rather than
    * `os.read.lines`, whose decoder REPORTS instead: a tear that cuts a
    * multi-byte UTF-8 sequence — reachable, since stage and agent names are
    * free-form and jsoniter emits them unescaped — would otherwise throw out of
    * the line iterator and lose the whole file, including the lines before the
    * tear.
    */
  def read(): List[CostRecord] =
    if !os.exists(path) then Nil
    else
      String(os.read.bytes(path), StandardCharsets.UTF_8)
        .split('\n')
        .iterator
        .filter(_.nonEmpty)
        .flatMap: line =>
          try Some(readFromString[CostRecord](line)(using CostRecord.codec))
          catch case NonFatal(_) => None
        .toList
