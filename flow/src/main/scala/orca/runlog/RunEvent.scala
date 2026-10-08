package orca.runlog

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.{AttemptId, StagePath}
import orca.agents.{BackendTag, JsonData, SessionKey}
import orca.events.{Cost, StageOutcome}
import orca.gitref.BranchName

import java.time.Instant

/** One line of a run's event log, `.orca/cache/runs/<key>/events.jsonl` (ADR
  * 0025). A public format: each line is a JSON object whose `type` is the case
  * name. Within one [[RunEvent.Schema]], changes are additive only (new cases,
  * new optional fields).
  *
  * `at` is when the event happened and `attempt` the attempt that wrote it. A
  * `stage` is the full stage path in [[StagePath.codec]]'s encoding; on
  * [[SessionCommitted]] and [[Turn]] it is the innermost open stage, `None`
  * outside any stage.
  */
private[orca] enum RunEvent:
  def at: Instant
  def attempt: AttemptId

  /** The attempt started. `trace` is the path of its trace log, if it has one.
    */
  case AttemptStarted(
      at: Instant,
      attempt: AttemptId,
      schema: Int,
      orcaVersion: String,
      flow: Option[String],
      workDir: String,
      pid: Long,
      trace: Option[String]
  )

  case BranchBound(at: Instant, attempt: AttemptId, branch: BranchName)

  case StageStarted(at: Instant, attempt: AttemptId, stage: StagePath.Stage)

  case StageEnded(
      at: Instant,
      attempt: AttemptId,
      stage: StagePath.Stage,
      outcome: StageOutcome
  )

  /** `agent.session(name, seed)` minted the durable session `id`. */
  case SessionMinted(
      at: Instant,
      attempt: AttemptId,
      name: String,
      stage: StagePath,
      id: String,
      seed: String,
      backend: BackendTag
  )

  /** The durable session `id` learnt the wire id to resume it with. */
  case SessionWireId(
      at: Instant,
      attempt: AttemptId,
      id: String,
      wireId: String
  )

  /** A turn committed to a session; written on every commit, so repeated.
    * `minted` is the key of a durable session, `None` for an ephemeral one.
    */
  case SessionCommitted(
      at: Instant,
      attempt: AttemptId,
      backend: BackendTag,
      wireId: Option[String],
      conversationKey: String,
      agent: String,
      role: Option[String],
      minted: Option[SessionKey],
      stage: Option[StagePath.Stage]
  )

  /** One LLM turn's spend. `turn` is its 1-based position among its call's
    * turns. `model` is `None` when neither the backend nor the caller named
    * one; `cost` is `None` when the model is not priced.
    */
  case Turn(
      at: Instant,
      attempt: AttemptId,
      agent: String,
      role: Option[String],
      model: Option[String],
      stage: Option[StagePath.Stage],
      turn: Int,
      apiCalls: Option[Long],
      usage: TurnUsage,
      cost: Option[Cost],
      conversationKey: String
  )

  /** The run succeeded; session records before this event no longer apply.
    * `published` is the PR/MR reference, if one was published.
    */
  case RunSucceeded(
      at: Instant,
      attempt: AttemptId,
      branch: BranchName,
      published: Option[String]
  )

  case AttemptFinished(
      at: Instant,
      attempt: AttemptId,
      outcome: AttemptOutcome
  )

private[orca] object RunEvent:
  /** The schema number every [[AttemptStarted]] records. */
  val Schema: Int = 1

  private given JsonValueCodec[StagePath.Stage] = StagePath.stageJsonData.codec

  private given JsonValueCodec[BranchName] =
    summon[JsonData[BranchName]].codec

  private given JsonValueCodec[StageOutcome] =
    ConfiguredJsonValueCodec.derived[StageOutcome](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )

  given codec: JsonValueCodec[RunEvent] =
    ConfiguredJsonValueCodec.derived[RunEvent](using
      CodecMakerConfig.withDiscriminatorFieldName(Some("type"))
    )

  /** The event as one JSON line, without the newline. */
  def encodeLine(event: RunEvent): String = writeToString(event)

  /** The event on `line`; `None` when it does not decode. */
  def decodeLine(line: String): Option[RunEvent] =
    try Some(readFromString[RunEvent](line))
    catch case _: JsonReaderException => None
