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

/** The persisted projection of [[orca.events.Usage]]'s token axes, sharing its
  * normalisation contract — including that cache reads and cache writes are
  * disjoint sub-portions of `inputTokens`, carried separately because they bill
  * at opposite ends of base input.
  *
  * The field names are `Usage`'s, verbatim, and so are the JSON keys: every
  * axis persisted here has to be traceable to the one it mirrors, or the two
  * drift and a reader silently reports the wrong money.
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
  val empty: ManifestUsage = of(Usage.empty)

  def of(usage: Usage): ManifestUsage = ManifestUsage(
    inputTokens = usage.inputTokens,
    outputTokens = usage.outputTokens,
    cacheReadInputTokens = usage.cacheReadInputTokens,
    cacheWriteInputTokens = usage.cacheWriteInputTokens,
    reasoningOutputTokens = usage.reasoningOutputTokens
  )

/** One breakdown bucket of [[ManifestCostSummary]]. `key` is `None` for the
  * untagged bucket — calls from an agent with no role, or tokens spent outside
  * any stage.
  */
private[orca] case class ManifestSubtotal(
    key: Option[String],
    usage: ManifestUsage,
    cost: Option[Cost]
)

/** A run's spend, folded from `TokensUsed`. The three breakdowns are the same
  * calls grouped three ways, so each sums back to `total`.
  *
  * `usage` covers every call; `cost` covers only the calls that had one to
  * resolve, so a run using a model absent from the pricing table shows tokens
  * against no dollars. A failed turn contributes nothing on any backend but
  * claude, which is the only one that attaches usage to a turn failure.
  */
private[orca] case class ManifestCostSummary(
    total: ManifestUsage,
    cost: Option[Cost],
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
  *
  * `promptTokens` of zero reads as "no request observed", not "no request": it
  * is the signature of a turn the CLI settles from leftover session state
  * without calling the API, but every backend also defaults its usage counters
  * to zero when a terminal frame omits them, and claude takes its cost from a
  * separate field — so a zero-token turn can still carry a reported cost.
  *
  * `attempt` is the turn's 1-based position among the turns of its call (see
  * [[orca.events.OrcaEvent.TokensUsed]]); turns with `attempt > 1` are what
  * retries added to the run's spend.
  *
  * `session` is the key the turn's conversation is recorded under in
  * [[RunManifest.sessions]] — `ManifestSession.wireId` when there is one, else
  * the client id. It makes "the first turn of a session" exact; without it the
  * only way to spot one is a `agent` name that happens to be unique to a single
  * session, which holds for reviewers and fails for the implementer.
  *
  * `apiCalls` is how many model requests the turn made — see
  * [[orca.events.Usage.apiCalls]], including why `None` is not one. Every call
  * re-sends the conversation so far, so `promptTokens / apiCalls` is the MEAN
  * prompt per call and no single call matches it: the first is the smallest and
  * the last the largest. A turn's fixed floor is the first call's prompt, which
  * this file does not record — only the mean is derivable here.
  *
  * `at` is stamped when the writer records the turn, not when the tokens were
  * spent: the event crosses an actor mailbox first.
  */
private[orca] case class ManifestTurn(
    at: String,
    agent: String,
    role: Option[String],
    stage: Option[String],
    promptTokens: Long,
    attempt: Int,
    session: Option[String],
    apiCalls: Option[Long]
)

/** Schema v5 (ADR 0021 §8) of a per-run manifest written to
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
  * no agent transcript involved — subject to the reporting gaps noted on
  * [[ManifestCostSummary]] and [[ManifestTurn]].
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
    * the wire shape changes. Readers skip any file whose version differs, in
    * either direction: orca owes no compatibility across 0.x shapes, and
    * `.orca/cache/runs/` is pruned cache data, so an unreadable run costs a
    * "continue" offer for that run and nothing else.
    */
  val SupportedVersion = 5

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
