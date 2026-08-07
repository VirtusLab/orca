package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.agents.JsonData

import java.time.Instant

/** How a run ended, as [[RunManifest.outcome]] records it. `Running` until
  * [[RunManifestWriter.finish]] finalizes it.
  */
private[orca] enum ManifestOutcome:
  case Running, Succeeded, Failed

  /** A spelling this build doesn't know — a manifest from a newer build. Only
    * the decoder produces it; nothing here ever writes one.
    */
  case Unknown(raw: String)

  /** The spelling on disk. An [[Unknown]] answers with the string it decoded
    * from, so passing a manifest through this build never respells it.
    */
  def wireName: String = this match
    case Running      => "running"
    case Succeeded    => "succeeded"
    case Failed       => "failed"
    case Unknown(raw) => raw

private[orca] object ManifestOutcome:
  private val known: List[ManifestOutcome] = List(Running, Succeeded, Failed)

  given codec: JsonValueCodec[ManifestOutcome] with
    def decodeValue(
        in: JsonReader,
        default: ManifestOutcome
    ): ManifestOutcome =
      in.readString(null) match
        case null => in.decodeError("expected an outcome string")
        case raw  => known.find(_.wireName == raw).getOrElse(Unknown(raw))
    def encodeValue(value: ManifestOutcome, out: JsonWriter): Unit =
      out.writeVal(value.wireName)
    // Only the pre-parse initializer jsoniter overwrites with the decoded
    // value: an absent `outcome` fails as a missing required field, and an
    // explicit JSON null is refused above.
    def nullValue: ManifestOutcome = Unknown("")

/** How a manifest session was opened, as [[ManifestSession.kind]] records it.
  */
private[orca] enum ManifestSessionKind:
  case Durable, OneShot

  /** See [[ManifestOutcome.Unknown]]. */
  case Unknown(raw: String)

  /** See [[ManifestOutcome.wireName]]. */
  def wireName: String = this match
    case Durable      => "durable"
    case OneShot      => "oneShot"
    case Unknown(raw) => raw

private[orca] object ManifestSessionKind:
  private val known: List[ManifestSessionKind] = List(Durable, OneShot)

  /** `Durable` exactly when the commit event carries the name an
    * `agent.session(name, seed)` call minted the session under.
    */
  def of(sessionName: Option[String]): ManifestSessionKind =
    if sessionName.isDefined then Durable else OneShot

  given codec: JsonValueCodec[ManifestSessionKind] with
    def decodeValue(
        in: JsonReader,
        default: ManifestSessionKind
    ): ManifestSessionKind =
      in.readString(null) match
        case null => in.decodeError("expected a session kind string")
        case raw  => known.find(_.wireName == raw).getOrElse(Unknown(raw))
    def encodeValue(value: ManifestSessionKind, out: JsonWriter): Unit =
      out.writeVal(value.wireName)
    // See ManifestOutcome.codec's nullValue.
    def nullValue: ManifestSessionKind = Unknown("")

/** One tracked session inside a [[RunManifest]] (ADR 0021 §8). `wireId` is the
  * persistable id ([[orca.agents.Agent.resumeWireId]]) — `None` when nothing
  * durable is known for the session, which is exactly when [[resumable]] is
  * `false` and `reason` explains why. Every backend keeps durable sessions, so
  * `None` means the id isn't known yet, not that the backend can't resume.
  */
private[orca] case class ManifestSession(
    harness: String,
    wireId: Option[String],
    reason: Option[String],
    agent: String,
    role: Option[String],
    stage: Option[String],
    sessionName: Option[String],
    kind: ManifestSessionKind,
    firstSeenAt: Instant,
    lastActiveAt: Instant
):
  /** Derived, not persisted: a session is resumable exactly when it carries a
    * wire id, so there is nothing here that could drift from [[wireId]].
    */
  def resumable: Boolean = wireId.isDefined

/** A per-run manifest written to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`, read by the shell to
  * offer "continue a session". A stale [[ManifestOutcome.Running]] with a dead
  * `pid` means the run crashed, and the shell still offers its recorded
  * sessions.
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
    startedAt: Instant,
    finishedAt: Option[Instant],
    outcome: ManifestOutcome,
    sessions: List[ManifestSession]
)

private[orca] object RunManifest:
  // Only a jsoniter codec — no `JsonData`/`Schema` half, deliberately: the
  // manifest crosses the process/disk boundary to the shell, never an HTTP or
  // LLM boundary, so it needs on-disk (de)serialisation but no tool schema.
  //
  // Strict (`JsonData.strictCodecConfig`) even with the version gate gone.
  // Strictness constrains only collection fields, and `sessions` is the one
  // whose absence must not read as empty: the menu renders the count verbatim,
  // so a silently empty list becomes a "(0 session(s))" row leading nowhere.
  // Reading an older build's file is unaffected — jsoniter skips unknown fields
  // under any config, and an `Option`/defaulted field stays optional under a
  // strict one. The cost is that a collection added later must be wrapped in
  // `Option`, or it lands on every reader as newly required.
  given codec: ConfiguredJsonValueCodec[RunManifest] =
    ConfiguredJsonValueCodec.derived[RunManifest](using
      JsonData.strictCodecConfig
    )
