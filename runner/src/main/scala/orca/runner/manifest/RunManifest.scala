package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.JsonData
import orca.events.Usage

/** One tracked session inside a [[RunManifest]] (ADR 0021 §8). `wireId` is the
  * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` when nothing
  * durable is known for the session, which is exactly when [[resumable]] is
  * `false` and `reason` explains why. Every backend keeps durable sessions, so
  * `None` means the id isn't known yet, not that the backend can't resume.
  * `kind` is `"durable"` when the writer joins `clientId` to a `SessionRecord`
  * in the progress log (an `agent.session(...)` call), `"oneShot"` otherwise —
  * a plain `agent.run`/`resultAs` one-shot AND an interactive call both land as
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

/** A per-run manifest written to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`, read by the shell to
  * offer "continue a session". `outcome` is `"running"` until
  * [[RunManifestWriter.finish]] finalizes it to `"succeeded"` or `"failed"` — a
  * stale `"running"` with a dead `pid` means the run crashed, and the shell
  * still offers its recorded sessions.
  *
  * Carries no schema version (ADR 0021 §8 amendment, 2026-08-05). The
  * compatibility rule is about writing instead: additions are `Option` or carry
  * a default, and nothing is renamed, retyped, or has its wire strings
  * respelled. A field this build doesn't declare is skipped, which is what lets
  * a manifest written by an earlier build still list its sessions. The rule is
  * only as good as `RunManifestGoldenTest`, which decodes a frozen file no
  * schema change may edit.
  *
  * Carries no cost or turn data: that lives in the run's `<id>-cost.jsonl`
  * ([[CostLog]]), which this file neither references nor requires.
  */
private[orca] case class RunManifest(
    orcaVersion: String,
    flow: Option[String],
    workDir: String,
    pid: Long,
    startedAt: String,
    finishedAt: Option[String],
    outcome: String,
    sessions: List[ManifestSession]
)

private[orca] object RunManifest:
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
  //
  // Strict (`JsonData.strictCodecConfig`) even with the version gate gone:
  // strictness only forces COLLECTION fields to be present, and `sessions` is
  // the one whose absence must not read as empty — the menu renders the count
  // verbatim, so a silently empty list becomes a "(0 session(s))" row leading
  // nowhere. Cross-version tolerance is unaffected: jsoniter skips unknown
  // fields under any config, and an added `Option`/defaulted field is optional
  // under a strict one. A collection added later must be wrapped in `Option`,
  // or it lands on every reader as newly required.
  //
  // Deliberately NOT the progress log's `withRequireCollectionFields(false)`:
  // that would make `sessions` optional, which is the one thing this format
  // cannot afford.
  given codec: ConfiguredJsonValueCodec[RunManifest] =
    ConfiguredJsonValueCodec.derived[RunManifest](using
      JsonData.strictCodecConfig
    )
