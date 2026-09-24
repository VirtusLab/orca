package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.events.{Cost, OrcaEvent, Usage}

import java.time.Instant

/** The persisted projection of [[orca.events.Usage]]'s token axes.
  *
  * The field names are `Usage`'s and so are the JSON keys: every axis persisted
  * here has to be traceable to the one it mirrors, or the two drift and a
  * reader silently reports the wrong money. `CostLogTest` pins that
  * correspondence against `Usage`'s own field list, so an axis added there
  * fails a test instead of silently vanishing from the log. The input axes are
  * therefore disjoint, as they are on `Usage`, and the total prompt is their
  * sum. `apiCalls`, an axis of `Usage`, isn't here at all: it lives on
  * [[CostRecord]], beside the attribution fields.
  *
  * Deliberately carries no money, unlike `Usage`: `Usage.cost` is only the
  * portion backends reported, and an unlabelled figure next to a resolved
  * [[orca.events.Cost]] is how reported and estimated spend get mixed.
  */
private[orca] case class CostLogUsage(
    freshInputTokens: Long,
    cacheReadInputTokens: Long,
    cacheWriteInputTokens: Long,
    outputTokens: Long,
    reasoningOutputTokens: Long
)

private[orca] object CostLogUsage:
  def of(usage: Usage): CostLogUsage = CostLogUsage(
    freshInputTokens = usage.freshInputTokens,
    cacheReadInputTokens = usage.cacheReadInputTokens,
    cacheWriteInputTokens = usage.cacheWriteInputTokens,
    outputTokens = usage.outputTokens,
    reasoningOutputTokens = usage.reasoningOutputTokens
  )

/** One LLM turn, one line of a `<AttemptId>.cost.jsonl` cost log (ADR 0021 §8
  * amendment, 2026-08-05). Carries every axis an aggregate needs: total,
  * by-role, by-agent, by-model and by-stage are all folds over these lines, so
  * an axis missing here cannot be recovered. The attempt's identity, flow and
  * outcome live in its [[AttemptManifest]], which is always present beside the
  * log.
  *
  * `model` is `None` when the backend reported none and the caller pinned none,
  * mirroring `OrcaEvent.UnpricedTurn.model`. `cost` is `None` for a model
  * absent from the pricing table, so such a turn shows tokens against no
  * dollars. `turn` is the turn's 1-based position among the turns of its call,
  * so retried spend is separable. `session` is the conversation key
  * (`OrcaEvent.conversationKey`): the session's `wireId` in
  * [[AttemptManifest.sessions]] once it has one, else a client id the manifest
  * does not carry.
  */
private[orca] case class CostRecord(
    at: Instant,
    agent: String,
    role: Option[String],
    model: Option[String],
    stage: Option[String],
    turn: Int,
    apiCalls: Option[Long],
    usage: CostLogUsage,
    cost: Option[Cost],
    session: Option[String]
)

private[orca] object CostRecord:
  given codec: ConfiguredJsonValueCodec[CostRecord] =
    ConfiguredJsonValueCodec.derived[CostRecord]

  /** The record of one `TokensUsed` turn, observed `at` while `stage` was the
    * innermost open stage.
    */
  def of(
      t: OrcaEvent.TokensUsed,
      at: Instant,
      stage: Option[String]
  ): CostRecord = CostRecord(
    at = at,
    agent = t.spend.agent,
    role = t.spend.role,
    model = t.spend.model.map(_.name),
    stage = stage,
    turn = t.spend.turn,
    apiCalls = t.spend.usage.apiCalls,
    usage = CostLogUsage.of(t.spend.usage),
    cost = t.cost,
    session = t.spend.session
  )

/** Append-only writer for one attempt's `<AttemptId>.cost.jsonl`.
  *
  * Appending, unlike the manifest's whole-file rewrite, is not self-healing: a
  * swallowed append is that turn gone for good. Accepted — this file is
  * measurement, and turns are frequent enough that rewriting would re-serialise
  * the whole log on every write.
  *
  * Not thread-safe, and doesn't need to be: the only caller is
  * [[AttemptManifestWriterState]], which an Ox actor serialises onto one
  * thread. That caller ensures the directory before the first append.
  */
private[manifest] class CostLog(path: os.Path):

  def append(record: CostRecord): Unit =
    os.write.append(path, s"${writeToString(record)(using CostRecord.codec)}\n")
