package orca.runlog

import orca.{AttemptId, OrcaDir, RunKey, StagePath}
import orca.events.{OrcaEvent, OrcaListener}
import orca.gitref.BranchName
import orca.sessions.SessionRecord
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.time.Instant
import scala.util.control.NonFatal

/** The attempt's writer of its run's event log,
  * `.orca/cache/runs/<key>/events.jsonl` (ADR 0025), and the run's
  * durable-session records read back from it.
  *
  * Every call waits until the in-memory projection is updated, so [[records]]
  * sees every earlier [[upsert]]; appending to the file happens in the
  * background, in call order. No call throws: a failed append is logged and
  * dropped.
  */
private[orca] trait RunEventLog extends OrcaListener:
  /** The run's durable-session records since its last success, in mint order.
    */
  def records(): List[SessionRecord]

  /** Upserts `record` by its [[orca.agents.SessionKey]]. Writes nothing when
    * the run already holds this exact record.
    */
  def upsert(record: SessionRecord): Unit

  /** Records the run's success on `branch`, with the published PR/MR reference;
    * [[records]] is empty afterwards.
    */
  def runSucceeded(branch: BranchName, published: Option[String]): Unit

  /** Records the attempt's end and returns once every event is in the file.
    * Called once, last.
    */
  def finish(outcome: AttemptOutcome): Unit

private[orca] object RunEventLog:

  /** Prunes the run cache ([[RunPruning]]), loads the session records from the
    * run's event log and appends the attempt's [[RunEvent.AttemptStarted]]. The
    * actors live as long as the enclosing scope, which must span every call up
    * to [[RunEventLog.finish]].
    *
    * `flowName` is the launched script's filename; `tracePath` the attempt's
    * trace log, if it has one.
    */
  def start(
      workDir: os.Path,
      runKey: RunKey,
      attemptId: AttemptId,
      orcaVersion: String,
      flowName: Option[String],
      tracePath: Option[os.Path],
      clock: () => Instant
  )(using Ox, BufferCapacity): RunEventLog =
    val runDir = OrcaDir.ensureRunDir(workDir, runKey)
    prune(workDir, runKey, runDir)
    val eventLog = OrcaDir.eventLogPath(workDir, runKey)
    val loaded = SessionProjection.records(RunEventReader.read(eventLog))
    endTornLine(eventLog)
    val appender = Actor.create(EventAppender(eventLog))
    val owner = Actor.create(
      ProjectionOwner(Projection(loaded, Nil), attemptId, clock, appender)
    )
    owner.ask(
      _.started(
        RunEvent.AttemptStarted(
          clock(),
          attemptId,
          schema = RunEvent.Schema,
          orcaVersion = orcaVersion,
          flow = flowName,
          workDir = workDir.toString,
          pid = attemptId.pid,
          trace = tracePath.map(_.toString)
        )
      )
    )
    ActorRunEventLog(owner, appender)

  // A crash mid-append leaves a last line without its newline; the next
  // append would join it, and neither line would decode.
  private def endTornLine(eventLog: os.Path): Unit =
    try
      if os.isFile(eventLog) then
        val size = os.size(eventLog)
        if size > 0 && os.read.bytes(eventLog, size - 1, 1).head != '\n' then
          os.write.append(eventLog, "\n")
    catch
      case NonFatal(e) =>
        log.warn(s"ending the torn last line of $eventLog failed", e)

  private val log = LoggerFactory.getLogger("orca.flow")

  private def prune(workDir: os.Path, runKey: RunKey, runDir: os.Path): Unit =
    RunPruning.removeLegacy(OrcaDir.cachePath(workDir))
    RunPruning.pruneRuns(OrcaDir.cacheRunsPath(workDir), runKey)
    RunPruning.pruneTraces(runDir)

private class ActorRunEventLog(
    owner: ActorRef[ProjectionOwner],
    appender: ActorRef[EventAppender]
) extends RunEventLog:
  def onEvent(event: OrcaEvent): Unit = owner.ask(_.observed(event))
  def records(): List[SessionRecord] = owner.ask(_.records)
  def upsert(record: SessionRecord): Unit = owner.ask(_.upserted(record))

  def runSucceeded(branch: BranchName, published: Option[String]): Unit =
    owner.ask(_.succeeded(branch, published))

  def finish(outcome: AttemptOutcome): Unit =
    owner.ask(_.finished(outcome))
    // The appender handles its mailbox in order, so this returns after every
    // queued append.
    appender.ask(_ => ())

/** What the attempt knows of its run: the session records, and the open stages
  * innermost first. Each transition returns the next projection with the events
  * that record it.
  */
private case class Projection(
    records: List[SessionRecord],
    openStages: List[StagePath.Stage]
):
  /** The projection after `event`, with the events it maps to, stamped with
    * `at` and `attempt`; `Left` when `event` cannot be recorded.
    */
  def observe(
      event: OrcaEvent,
      at: Instant,
      attempt: AttemptId
  ): (Projection, Either[String, List[RunEvent]]) =
    val events = eventsOf(event, at, attempt)
    (withRecordsAfter(events.getOrElse(Nil)).withStagesAfter(event), events)

  /** The projection after upserting `record` by its key, with the events that
    * record it: a new key mints; a known key whose record differs only by a
    * newly learnt wire id gets that id; any other difference re-mints in place;
    * an identical record records nothing.
    */
  def upsert(
      record: SessionRecord,
      at: Instant,
      attempt: AttemptId
  ): (Projection, List[RunEvent]) =
    recording(upsertEvents(record, at, attempt))

  /** The projection after `events`, with `events`. */
  def recording(events: List[RunEvent]): (Projection, List[RunEvent]) =
    (withRecordsAfter(events), events)

  private def withRecordsAfter(events: List[RunEvent]): Projection =
    copy(records = events.foldLeft(records)(SessionProjection.applied))

  private def withStagesAfter(event: OrcaEvent): Projection = event match
    case OrcaEvent.StageStarted(path) => copy(openStages = path :: openStages)
    case _: OrcaEvent.StageEnded      => copy(openStages = openStages.drop(1))
    case _                            => this

  private def eventsOf(
      event: OrcaEvent,
      at: Instant,
      attempt: AttemptId
  ): Either[String, List[RunEvent]] =
    event match
      case OrcaEvent.StageStarted(path) =>
        Right(List(RunEvent.StageStarted(at, attempt, path)))
      case OrcaEvent.StageEnded(path, outcome) =>
        Right(List(RunEvent.StageEnded(at, attempt, path, outcome)))
      case OrcaEvent.BranchBound(branch) =>
        BranchName
          .parse(branch)
          .map(b => List(RunEvent.BranchBound(at, attempt, b)))
      case e: OrcaEvent.SessionCommitted =>
        Right(List(committed(e, at, attempt)))
      case t: OrcaEvent.TokensUsed =>
        Right(List(turn(t, at, attempt)))
      case _ => Right(Nil)

  private def upsertEvents(
      record: SessionRecord,
      at: Instant,
      attempt: AttemptId
  ): List[RunEvent] =
    val wireId = record.resumeWireId.map(w =>
      RunEvent.SessionWireId(at, attempt, id = record.id, wireId = w)
    )
    records.find(_.key == record.key) match
      case Some(existing) if existing == record => Nil
      case Some(existing)
          if record.copy(resumeWireId = existing.resumeWireId) == existing =>
        wireId.toList
      case _ => minted(record, at, attempt) :: wireId.toList

  private def minted(
      record: SessionRecord,
      at: Instant,
      attempt: AttemptId
  ): RunEvent.SessionMinted =
    RunEvent.SessionMinted(
      at,
      attempt,
      name = record.name,
      stage = record.stage,
      id = record.id,
      seed = record.seed,
      backend = record.backend
    )

  private def committed(
      e: OrcaEvent.SessionCommitted,
      at: Instant,
      attempt: AttemptId
  ): RunEvent.SessionCommitted =
    RunEvent.SessionCommitted(
      at,
      attempt,
      backend = e.backend,
      wireId = e.wireId,
      conversationKey = OrcaEvent.conversationKey(e.clientId, e.wireId),
      agent = e.agent,
      role = e.role,
      minted = e.sessionKey,
      stage = openStages.headOption
    )

  private def turn(
      t: OrcaEvent.TokensUsed,
      at: Instant,
      attempt: AttemptId
  ): RunEvent.Turn =
    RunEvent.Turn(
      at,
      attempt,
      agent = t.spend.agent,
      role = t.spend.role,
      model = t.spend.model.map(_.name),
      stage = openStages.headOption,
      turn = t.spend.turn,
      apiCalls = t.spend.usage.apiCalls,
      usage = TurnUsage.of(t.spend.usage),
      cost = t.cost,
      conversationKey = t.spend.conversationKey
    )

/** Owns the [[Projection]]; ADR 0025 records why it is a `var` in an actor.
  * Every method runs on the actor's thread, never throws, and hands the events
  * it records to `appender`.
  */
private class ProjectionOwner(
    initial: Projection,
    attempt: AttemptId,
    clock: () => Instant,
    appender: ActorRef[EventAppender]
):
  private val log = LoggerFactory.getLogger("orca.flow")

  private var state = initial

  def records: List[SessionRecord] = state.records

  def started(event: RunEvent.AttemptStarted): Unit =
    guarded("recording the attempt's start"):
      advance(state.recording(List(event)))

  def observed(event: OrcaEvent): Unit =
    guarded(s"recording ${event.getClass.getSimpleName}"):
      val (next, events) = state.observe(event, clock(), attempt)
      events match
        case Right(recorded) => advance((next, recorded))
        case Left(reason) =>
          state = next
          log.warn(s"event not recorded: $reason")

  def upserted(record: SessionRecord): Unit =
    guarded("recording a session"):
      advance(state.upsert(record, clock(), attempt))

  def succeeded(branch: BranchName, published: Option[String]): Unit =
    guarded("recording the run's success"):
      advance(
        state.recording(
          List(RunEvent.RunSucceeded(clock(), attempt, branch, published))
        )
      )

  def finished(outcome: AttemptOutcome): Unit =
    guarded("recording the attempt's end"):
      advance(
        state.recording(
          List(RunEvent.AttemptFinished(clock(), attempt, outcome))
        )
      )

  /** Moves to the next projection, then queues its events for the file. */
  private def advance(transition: (Projection, List[RunEvent])): Unit =
    val (next, events) = transition
    state = next
    events.foreach(e => appender.tell(_.append(e)))

  // A throw here would quarantine the listener (from `observed`) or reach the
  // flow's teardown (from the rest).
  private def guarded(what: String)(op: => Unit): Unit =
    try op
    catch case NonFatal(e) => log.warn(s"$what failed (best-effort)", e)

/** Appends events to the event log at `path`. Runs on its own actor, so the
  * attempt does not wait on disk; a failed append is logged and the event
  * dropped, since a throw would end the actor's scope.
  */
private class EventAppender(path: os.Path):
  private val log = LoggerFactory.getLogger("orca.flow")

  def append(event: RunEvent): Unit =
    try os.write.append(path, RunEvent.encodeLine(event) + "\n")
    catch
      case NonFatal(e) =>
        log.warn(s"appending to the run event log $path failed (dropped)", e)
