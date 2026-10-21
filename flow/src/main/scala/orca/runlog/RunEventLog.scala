package orca.runlog

import orca.{AttemptId, OrcaDir, RunKey, StagePath}
import orca.effect.FileSystem
import orca.events.{OrcaEvent, OrcaListener}
import orca.gitref.BranchName
import orca.sessions.{SessionRecord, SessionStore}
import orca.safe.{Logger, Ref, concurrency}
import ox.Ox
import ox.Fork
import ox.channels.{ActorRef, BufferCapacity, Channel}

import java.time.Instant
import orca.safe.prelude.NonFatal

/** The attempt's writer of its run's event log,
  * `.orca/cache/runs/<key>/events.jsonl` (ADR 0025), and the run's
  * durable-session records read back from it.
  *
  * Every call waits until the in-memory projection is updated; appending to the
  * file happens in the background, in call order. No call throws: a failed
  * append is logged and dropped, and so is an event recorded after [[finish]].
  */
private[orca] trait RunEventLog extends OrcaListener, SessionStore:
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
    * projection's actor and the file's writer fork live in the enclosing scope,
    * which must span every call up to [[RunEventLog.finish]].
    *
    * `flowName` is the launched script's filename; `tracePath` the attempt's
    * trace log, if it has one.
    */
  def start(
      fs: FileSystem,
      workDir: os.Path,
      runKey: RunKey,
      attemptId: AttemptId,
      orcaVersion: String,
      flowName: Option[String],
      tracePath: Option[os.Path],
      clock: () -> Instant
  )(using Ox, BufferCapacity): RunEventLog =
    val eventLog = OrcaDir(fs).ensureEventLog(workDir, runKey)
    prune(fs, workDir, runKey)
    val loaded = SessionProjection.records(RunEventReader(fs).read(eventLog))
    endTornLine(fs, eventLog)
    val queue = concurrency.bufferedDefaultChannel[RunEvent]
    val writer = concurrency.fork(
      concurrency
        .receiveUntilClosed(queue)
        .foreach(appendEvent(fs, eventLog, _))
    )
    val owner = concurrency.actor(
      ProjectionOwner(Projection(loaded, Nil), attemptId, clock, queue)
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
    ActorRunEventLog(owner, queue, writer)

  // A crash mid-append leaves a last line without its newline; the next
  // append would join it, and neither line would decode.
  private def endTornLine(fs: FileSystem, eventLog: os.Path): Unit =
    try
      if fs.isFile(eventLog) then
        if fs.readSuffix(eventLog, 1).headOption.exists(_ != '\n') then
          fs.append(eventLog, "\n")
    catch
      case NonFatal(e) =>
        log.warn(s"ending the torn last line of $eventLog failed", e)

  private[runlog] val log = Logger("orca.flow")

  private def prune(fs: FileSystem, workDir: os.Path, runKey: RunKey): Unit =
    val cacheRuns = OrcaDir.cacheRunsPath(workDir)
    val pruning = RunPruning(fs)
    pruning.removeLegacy(OrcaDir.cachePath(workDir))
    pruning.pruneRuns(cacheRuns, runKey)
    pruning.pruneTraces(cacheRuns)

private class ActorRunEventLog(
    owner: ActorRef[ProjectionOwner],
    queue: Channel[RunEvent],
    writer: Fork[Unit]
) extends RunEventLog:
  def onEvent(event: OrcaEvent): Unit = owner.ask(_.observed(event))
  def records(): List[SessionRecord] = owner.ask(_.records)
  def upsert(record: SessionRecord): Unit = owner.ask(_.upserted(record))

  def runSucceeded(branch: BranchName, published: Option[String]): Unit =
    owner.ask(_.succeeded(branch, published))

  def finish(outcome: AttemptOutcome): Unit =
    owner.ask(_.finished(outcome))
    // The writer drains the queue in order, so this returns after every
    // queued append.
    queue.done()
    writer.join()

/** What the attempt knows of its run: the session records, and the open stages
  * innermost first. Each transition returns the next projection with the events
  * that record it.
  */
private case class Projection(
    records: List[SessionRecord],
    openStages: List[StagePath.Stage]
):
  /** The projection after `event`, with the events it maps to, stamped with
    * `at` and `attempt`.
    */
  def observe(
      event: OrcaEvent,
      at: Instant,
      attempt: AttemptId
  ): (Projection, List[RunEvent]) =
    val events = eventsOf(event, at, attempt)
    (withRecordsAfter(events).withStagesAfter(event), events)

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
  ): List[RunEvent] =
    event match
      case OrcaEvent.StageStarted(path) =>
        List(RunEvent.StageStarted(at, attempt, path))
      case OrcaEvent.StageEnded(path, outcome) =>
        List(RunEvent.StageEnded(at, attempt, path, outcome))
      case OrcaEvent.BranchBound(branch) =>
        List(RunEvent.BranchBound(at, attempt, branch))
      case e: OrcaEvent.SessionCommitted => List(committed(e, at, attempt))
      case t: OrcaEvent.TokensUsed       => List(turn(t, at, attempt))
      case _                             => Nil

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

/** Owns the [[Projection]]. Every method runs on the actor's thread, never
  * throws, and queues the events it records for the writer fork.
  */
private class ProjectionOwner(
    initial: Projection,
    attempt: AttemptId,
    clock: () -> Instant,
    queue: Channel[RunEvent]
):
  import RunEventLog.log

  // The actor serialises every read and replacement, so plain get/set
  // suffice: a `var` would do, but safe mode rejects `var` fields.
  private val state: Ref[Projection] = Ref(initial)

  def records: List[SessionRecord] = state.get.records

  def started(event: RunEvent.AttemptStarted): Unit =
    guarded("recording the attempt's start"):
      advance(state.get.recording(List(event)))

  def observed(event: OrcaEvent): Unit =
    guarded(s"recording ${event.getClass.getSimpleName}"):
      advance(state.get.observe(event, clock(), attempt))

  def upserted(record: SessionRecord): Unit =
    guarded("recording a session"):
      advance(state.get.upsert(record, clock(), attempt))

  def succeeded(branch: BranchName, published: Option[String]): Unit =
    guarded("recording the run's success"):
      advance(
        state.get.recording(
          List(RunEvent.RunSucceeded(clock(), attempt, branch, published))
        )
      )

  def finished(outcome: AttemptOutcome): Unit =
    guarded("recording the attempt's end"):
      advance(
        state.get.recording(
          List(RunEvent.AttemptFinished(clock(), attempt, outcome))
        )
      )

  /** Moves to the next projection, then queues its events for the file. */
  private def advance(transition: (Projection, List[RunEvent])): Unit =
    val (next, events) = transition
    state.set(next)
    events.foreach(queue.send)

  // A throw here would quarantine the listener (from `observed`) or reach the
  // flow's teardown (from the rest).
  private def guarded(what: String)(op: => Unit): Unit =
    try op
    catch case NonFatal(e) => log.warn(s"$what failed (best-effort)", e)

/** Appends `event` to the event log at `path`. Runs on the writer fork, so the
  * attempt does not wait on disk; a failed append is logged and the event
  * dropped, since a throw would end the writer's scope.
  */
private def appendEvent(fs: FileSystem, path: os.Path, event: RunEvent): Unit =
  try fs.append(path, RunEvent.encodeLine(event) + "\n")
  catch
    case NonFatal(e) =>
      RunEventLog.log
        .warn(s"appending to the run event log $path failed (dropped)", e)
