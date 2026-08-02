package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.JsonData
import orca.events.{Cost, Usage}

/** One tracked session inside a [[RunManifest]] (ADR 0021 §8). `wireId` is the
  * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` for backends
  * whose sessions don't survive the run (pi), which is exactly when
  * [[resumable]] is `false` and `reason` explains why. `kind` is `"durable"`
  * when the writer joins `clientId` to a `SessionRecord` in the progress log
  * (an `agent.session(...)` call), `"oneShot"` otherwise — a plain
  * `agent.run`/`resultAs` one-shot AND an interactive call both land as
  * `"oneShot"` today, since `SessionCommitted` carries nothing that tells them
  * apart (see [[RunManifestWriterState.durableSessionName]]).
  */
private[orca] case class ManifestSession(
    harness: String,
    wireId: Option[String],
    reason: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    sessionName: Option[String],
    kind: String,
    firstSeenAt: String,
    lastActiveAt: String
):
  /** Derived, not persisted: a session is resumable exactly when it carries a
    * wire id, so there is nothing here that could drift from [[wireId]].
    */
  def resumable: Boolean = wireId.isDefined

/** Token counts for one call or a group of them — the persisted projection of
  * [[orca.events.Usage]]'s token axes, sharing its normalisation contract
  * (`inputTokens` is inclusive of `cachedInputTokens`; `outputTokens` is
  * inclusive of `reasoningOutputTokens`).
  *
  * Deliberately carries no money: `Usage.cost` is only the portion backends
  * reported, and a figure sitting next to a resolved [[ManifestCost]] without
  * saying which is which is how a summary comes to mix reported and estimated
  * spend. [[ManifestUsage.of]] is the one place the axes are enumerated.
  */
private[orca] case class ManifestUsage(
    inputTokens: Long,
    outputTokens: Long,
    cachedInputTokens: Long,
    reasoningOutputTokens: Long
)

private[orca] object ManifestUsage:
  val empty: ManifestUsage = of(Usage.empty)

  def of(usage: Usage): ManifestUsage = ManifestUsage(
    inputTokens = usage.inputTokens,
    outputTokens = usage.outputTokens,
    cachedInputTokens = usage.cachedInputTokens,
    reasoningOutputTokens = usage.reasoningOutputTokens
  )

/** A USD figure with [[orca.events.Cost]]'s estimated flag preserved:
  * `estimated` is true when any call folded in was priced from the table rather
  * than reported by the backend, so an aggregate mixing the two reads as an
  * estimate and is never quoted as a billed figure.
  */
private[orca] case class ManifestCost(amount: BigDecimal, estimated: Boolean)

private[orca] object ManifestCost:
  def of(cost: Cost): ManifestCost = ManifestCost(cost.amount, cost.estimated)

/** One breakdown bucket of [[ManifestCostSummary]]. `key` is `None` for the
  * untagged bucket — calls from an agent with no role, or tokens spent outside
  * any stage. `cost` is absent when no call in the bucket had a reported cost
  * and none could be priced from the table.
  */
private[orca] case class ManifestSubtotal(
    key: Option[String],
    usage: ManifestUsage,
    cost: Option[ManifestCost]
)

/** A run's whole spend, folded from `TokensUsed`. The three breakdowns share
  * the same calls, so each sums back to `total`.
  */
private[orca] case class ManifestCostSummary(
    total: ManifestUsage,
    cost: Option[ManifestCost],
    byRole: List[ManifestSubtotal],
    byAgent: List[ManifestSubtotal],
    byStage: List[ManifestSubtotal]
)

private[orca] object ManifestCostSummary:
  val empty: ManifestCostSummary =
    ManifestCostSummary(ManifestUsage.empty, None, Nil, Nil, Nil)

/** One LLM turn: what it belonged to and how large its prompt was. The prompt
  * size is what makes per-turn prefix growth measurable across a run; the
  * `agent`/`role`/`stage` keys match [[ManifestCostSummary]]'s breakdowns so a
  * subtotal can be traced back to the turns that produced it.
  */
private[orca] case class ManifestTurn(
    at: String,
    agent: String,
    role: Option[String],
    stage: Option[String],
    promptTokens: Long
):
  /** Derived, not persisted (like [[ManifestSession.resumable]]): a turn that
    * reached the provider always carries a prompt, so zero prompt tokens means
    * the CLI answered from leftover session state without an API call.
    */
  def apiCall: Boolean = promptTokens > 0

/** Schema v3 (ADR 0021 §8) of a per-run manifest written to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`, read by the shell to
  * offer "continue a session". `manifestVersion` is a hard gate: a reader
  * checks it before decoding and skips anything it doesn't write itself, rather
  * than guessing at an unfamiliar schema — that gate is the one place
  * cross-version tolerance lives, so the codec below doesn't need to be
  * tolerant too. `outcome` is `"running"` until [[RunManifestWriter.finish]]
  * finalizes it to `"succeeded"` or `"failed"` — a stale `"running"` with a
  * dead `pid` means the run crashed, and the shell still offers its recorded
  * sessions.
  *
  * `cost` and `turns` make a run's spend answerable from this file alone, with
  * no agent transcript involved; `turns.size` is the run's turn count.
  */
private[orca] case class RunManifest(
    manifestVersion: Int,
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    pid: Long,
    startedAt: String,
    finishedAt: Option[String],
    outcome: String,
    sessions: List[ManifestSession],
    cost: ManifestCostSummary,
    turns: List[ManifestTurn]
)

private[orca] object RunManifest:
  /** The manifest schema version this build reads and writes — bumped whenever
    * the wire shape changes (v3 added [[ManifestCostSummary]] and the per-turn
    * log). Readers skip any file whose version differs, in either direction:
    * orca owes no compatibility across 0.x shapes, and `.orca/cache/runs/` is
    * pruned cache data, so an unreadable older run costs a "continue" offer for
    * that run and nothing else. The writer ([[RunManifestWriterState.write]])
    * always passes this explicitly rather than relying on a default, so a
    * version bump can't silently stamp new manifests without the writer call
    * site being revisited.
    */
  val SupportedVersion = 3

  // The `outcome` and `ManifestSession.kind` wire strings, named once here so
  // the writer that produces them (RunManifestWriter's Outcome/SessionKind
  // enums) and every reader that matches on them share one spelling.
  val OutcomeRunning = "running"
  val OutcomeSucceeded = "succeeded"
  val OutcomeFailed = "failed"
  val KindDurable = "durable"
  val KindOneShot = "oneShot"
  val KindInteractive = "interactive"

  // Only a jsoniter codec — no `JsonData`/`Schema` half, deliberately: the
  // manifest crosses the process/disk boundary to the shell, never an HTTP or
  // LLM boundary, so it needs on-disk (de)serialisation but no tool schema.
  // Strict, like every other in-process codec (`JsonData.strictCodecConfig`):
  // a missing collection field fails to parse rather than silently defaulting
  // to empty. The `manifestVersion` gate above is the only cross-version
  // tolerance this format needs — a writer bug producing a malformed manifest
  // should fail loudly, not decode as a plausible-looking empty one.
  given codec: ConfiguredJsonValueCodec[RunManifest] =
    ConfiguredJsonValueCodec.derived[RunManifest](using
      JsonData.strictCodecConfig
    )
