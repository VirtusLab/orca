package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import orca.OrcaDir
import orca.events.{OrcaEvent, OrcaListener}
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.time.Instant
import scala.util.control.NonFatal

/** The manifest's `outcome` at finish, as a typed value rather than a bare
  * string, so a typo in a call site fails to compile instead of landing on
  * disk. The "running" state is internal-only ([[RunManifestWriterState]]'s
  * `Outcome.Running`) and never a finish input.
  */
private[orca] enum RunOutcome:
  case Succeeded, Failed

/** Always-attached listener (like [[LoggingListener]]) that writes the per-run
  * session manifest ([[RunManifest]], ADR 0021 §8) to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`. Rewrites the whole file
  * atomically (the `ProgressStore.writeLog` temp+move idiom) on every stage
  * transition and `SessionCommitted`, so a crashed run still leaves its
  * sessions on disk with `outcome: "running"` and a dead `pid` — the shell
  * treats that as "crashed, but still offers its sessions".
  *
  * `flowName` comes from `ORCA_FLOW_NAME`, set by the shell before exec'ing the
  * flow subprocess (`FlowLauncher.childEnv`); `runFlow` never sees the `.sc`
  * path itself, so a flow launched outside the shell leaves it unset and the
  * manifest's `flow` is `None`.
  *
  * Thread-safety and the lazy-creation gate are covered on
  * [[RunManifestWriterState]], which owns the actual mutable state.
  */
private[orca] trait RunManifestWriter extends OrcaListener:
  /** Finalizes the manifest: `outcome` and `finishedAt`, then a last write.
    * Called once from `flow()`'s `finally`. No-ops (writes and creates nothing)
    * if no `SessionCommitted` was ever seen — see the class scaladoc.
    */
  def finish(outcome: RunOutcome): Unit

private[orca] object RunManifestWriter:

  /** Build a production writer whose state is owned by an Ox actor in the given
    * scope (mirrors [[orca.runner.terminal.TerminalOutput.start]]). The actor
    * fork lives as long as the scope, which must span construction through
    * `finish`; `flow()` provides that scope.
    */
  def start(
      workDir: os.Path,
      orcaVersion: String,
      flowName: Option[String],
      clock: () => Instant
  )(using Ox, BufferCapacity): RunManifestWriter =
    val state =
      new RunManifestWriterState(workDir, orcaVersion, flowName, clock)
    new ActorRunManifestWriter(Actor.create(state))

/** Actor-backed [[RunManifestWriter]]. `onEvent` is a `tell`; `finish` is an
  * `ask` so its final write completes before the caller proceeds. A throw from
  * a `tell`'s handler would close the actor's channel — so the state guards
  * every write internally and neither entry point ever throws.
  */
private class ActorRunManifestWriter(actor: ActorRef[RunManifestWriterState])
    extends RunManifestWriter:
  def onEvent(event: OrcaEvent): Unit = actor.tell(_.onEvent(event))
  def finish(outcome: RunOutcome): Unit = actor.ask(_.finish(outcome))

/** Mutable manifest-building state — not thread-safe in isolation.
  * [[ActorRunManifestWriter]] serialises every call onto one actor thread:
  * `onEvent` is a `tell` (fire-and-forget, though a full mailbox blocks the
  * emitter — every event writes, but turns arrive seconds apart and an append
  * is one small write, so the queue still drains far faster than it fills) and
  * `finish` is an `ask` (its write must land before `flow()` moves on to the
  * cost summary). Every write is guarded internally ([[safeWrite]]) so a
  * transient failure can't quarantine the writer or throw out of a `tell`'s
  * handler. Tests construct this directly and drive events synchronously.
  *
  * The two files have separate creation gates: the manifest appears on the
  * first `SessionCommitted` (a session-less run offers nothing to continue —
  * ADR 0021 §8), the cost log on the first `TokensUsed`. They cannot share one,
  * because the autonomous text path emits a turn's tokens BEFORE committing its
  * session, so gating cost on the session event would mean holding turns in
  * memory until it opened. Stage events before either gate still update the
  * stage stack, so the first session and the first turn are both stamped with
  * the right stage.
  */
private[runner] class RunManifestWriterState(
    workDir: os.Path,
    orcaVersion: String,
    flowName: Option[String],
    clock: () => Instant
) extends RunManifestWriter:

  private val log = LoggerFactory.getLogger("orca.flow")

  /** The size of each of `pruneOldRuns`' two kept sets ([[keptRunIds]]) — a run
    * owns up to two files, and the sets overlap, so the runs directory holds
    * between this many and twice this many runs.
    */
  private val MaxKeptRuns = 20

  private val pid: Long = ProcessHandle.current().pid()
  private val startedAt: Instant = clock()
  private val runId: String = s"${startedAt.toEpochMilli}-$pid"
  private val manifestPath: os.Path =
    OrcaDir.cacheRunsPath(workDir) / s"$runId.json"

  /** The run's cost log. `.jsonl`, not `.json`: the shell's listing selects
    * `ext == "json"` (`ManifestReader.list`), so a `.json` sibling would reach
    * it as a manifest that fails to decode — one warning per run, on every menu
    * redraw.
    */
  private val costLog: CostLog =
    CostLog(OrcaDir.cacheRunsPath(workDir) / s"$runId-cost.jsonl")

  /** A tracked session plus the dedup key it was upserted under — `(harness,
    * wireId-or-clientId)`, per the event's dedup contract
    * (`OrcaEvent.SessionCommitted`'s scaladoc) — kept alongside the public
    * [[ManifestSession]] shape since the manifest itself never carries the raw
    * `clientId`.
    */
  private case class Entry(
      harness: String,
      dedupKey: String,
      session: ManifestSession
  )

  /** The manifest's `outcome` on the wire. `Running` is the only state without
    * a [[RunOutcome]] counterpart: it is the default until `finish` maps a
    * [[RunOutcome]] onto `Succeeded`/`Failed`.
    */
  private enum Outcome(val wireValue: String):
    case Running extends Outcome(RunManifest.OutcomeRunning)
    case Succeeded extends Outcome(RunManifest.OutcomeSucceeded)
    case Failed extends Outcome(RunManifest.OutcomeFailed)

  private def outcomeOf(finished: RunOutcome): Outcome = finished match
    case RunOutcome.Succeeded => Outcome.Succeeded
    case RunOutcome.Failed    => Outcome.Failed

  private case class State(
      stageStack: List[String] = Nil,
      entries: List[Entry] = Nil,
      // Gates the cost log's `Run` header and its `Finish` trailer: both are
      // written only for a run that actually spent tokens.
      anyTurnRecorded: Boolean = false,
      outcome: Outcome = Outcome.Running,
      finishedAt: Option[Instant] = None,
      prunedOnce: Boolean = false
  )

  private var state = State()

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      state = state.copy(stageStack = name :: state.stageStack)
      if hasCommittedSession then safeWrite()
    case OrcaEvent.StageCompleted(_) =>
      state.stageStack match
        case Nil =>
          log.warn(
            "unbalanced StageCompleted: stage stack already empty, ignoring"
          )
        case _ :: rest =>
          state = state.copy(stageStack = rest)
      if hasCommittedSession then safeWrite()
    case OrcaEvent
          .SessionCommitted(
            harness,
            clientId,
            wireId,
            sessionName,
            agent,
            role
          ) =>
      state = state.copy(entries =
        upsertSession(harness, clientId, wireId, sessionName, agent, role)
      )
      if hasCommittedSession then safeWrite()
    case t: OrcaEvent.TokensUsed =>
      if !state.anyTurnRecorded then
        guarded("cost log header"):
          costLog.append(
            CostRecord.Run(orcaVersion, flowName, workDir.toString)
          )
          state = state.copy(anyTurnRecorded = true)
          pruneOnce()
      guarded("cost log append"):
        costLog.append(
          CostRecord.Turn(
            at = clock().toString,
            agent = t.agent,
            role = t.role,
            stage = state.stageStack.headOption,
            attempt = t.attempt,
            apiCalls = t.usage.apiCalls,
            usage = ManifestUsage.of(t.usage),
            cost = t.cost,
            session = t.session
          )
        )
    case _ => ()

  /** Whether a `SessionCommitted` has ever landed — the manifest file's
    * existence gate. `state.entries` only ever grows or upserts in place (never
    * shrinks), so this is equivalent to "at least one session was committed so
    * far" without a separate flag.
    */
  private def hasCommittedSession: Boolean = state.entries.nonEmpty

  def finish(outcome: RunOutcome): Unit =
    // The manifest half is an idempotent rewrite; the cost half is an append,
    // so a second `finish` would leave a second trailer behind.
    val alreadyFinished = state.finishedAt.isDefined
    state = state.copy(outcome = outcomeOf(outcome), finishedAt = Some(clock()))
    if hasCommittedSession then safeWrite()
    if state.anyTurnRecorded && !alreadyFinished then
      guarded("cost log finish"):
        costLog.append(
          CostRecord.Finish(clock().toString, outcomeOf(outcome).wireValue)
        )

  /** The whole-file rewrite, guarded. Because it rewrites everything, a
    * swallowed failure self-heals on the next successful write — which the cost
    * log's appends do not (see [[CostLog]]).
    */
  private def safeWrite(): Unit = guarded("run manifest write")(write())

  /** Runs an IO step so a transient failure (e.g. ENOSPC) is logged and
    * swallowed rather than escaping. Both files need this and for the same
    * reason: a throw from a `tell`'s handler closes the actor's channel and
    * quarantines the writer for the rest of the run, and a throw from `finish`
    * would surface into run teardown.
    */
  private def guarded(what: String)(op: => Unit): Unit =
    try op
    catch case NonFatal(e) => log.warn(s"$what failed (best-effort)", e)

  /** Upsert-by-dedup-key (mirrors `ProgressStore`'s upsert idiom): the same
    * session re-firing `SessionCommitted` on a later turn (retries, resumed
    * durable calls) updates `stage`/`lastActiveAt`/`sessionName` in place
    * (last-write-wins), while `firstSeenAt` is preserved from the first
    * sighting.
    */
  private def upsertSession(
      harness: String,
      clientId: String,
      wireId: Option[String],
      sessionName: Option[String],
      agent: String,
      role: Option[String]
  ): List[Entry] =
    val key = OrcaEvent.sessionKey(clientId, wireId)
    val now = clock().toString
    val stage = state.stageStack.headOption
    val existing =
      state.entries.find(e => e.harness == harness && e.dedupKey == key)
    val session = ManifestSession(
      harness = harness,
      wireId = wireId,
      reason =
        if wireId.isEmpty then Some(s"$harness sessions do not survive the run")
        else None,
      agent = agent,
      role = role,
      stage = stage,
      sessionName = sessionName,
      kind = SessionKind.of(sessionName).wireValue,
      firstSeenAt = existing.map(_.session.firstSeenAt).getOrElse(now),
      lastActiveAt = now
    )
    val entry = Entry(harness, key, session)
    existing match
      case Some(_) =>
        state.entries.map: e =>
          if e.harness == harness && e.dedupKey == key then entry else e
      case None => state.entries :+ entry

  /** Atomic rewrite of the whole manifest — the `ProgressStore.writeLog`
    * temp+move idiom: a sibling temp file, then `os.move(atomicMove = true)` so
    * a crash mid-write never leaves a torn file behind.
    */
  private def write(): Unit =
    val manifest = RunManifest(
      orcaVersion = orcaVersion,
      flow = flowName,
      workDir = workDir.toString,
      pid = pid,
      startedAt = startedAt.toString,
      finishedAt = state.finishedAt.map(_.toString),
      outcome = state.outcome.wireValue,
      sessions = state.entries.map(_.session)
    )
    val dir = manifestPath / os.up
    val tmp = os.temp(
      contents = writeToString(manifest)(using RunManifest.codec),
      dir = dir,
      prefix = s".${manifestPath.last}.",
      suffix = ".tmp",
      deleteOnExit = false
    )
    try
      try os.move(tmp, manifestPath, replaceExisting = true, atomicMove = true)
      catch
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          os.move(tmp, manifestPath, replaceExisting = true)
    catch
      case NonFatal(e) =>
        if os.exists(tmp) then os.remove(tmp): Unit
        throw e
    pruneOnce()

  /** Prunes on the first write of either file, never again. Not inside
    * `write()`: a run that spends tokens without ever committing a session
    * never writes a manifest, so the trigger would never fire for exactly the
    * runs the cost log added.
    */
  private def pruneOnce(): Unit =
    if !state.prunedOnce then
      state = state.copy(prunedOnce = true)
      pruneOldRuns(OrcaDir.cacheRunsPath(workDir))

  /** Deletes every file of every run outside [[keptRunIds]]. Grouping by run id
    * rather than counting files is what keeps the budget in runs, and what
    * stops a cost log outliving the manifest it belongs to.
    *
    * Fully best-effort — the listing itself and each delete are both guarded —
    * since a failure here (a vanished dir, a concurrent cleanup) must not turn
    * into a quarantined listener or an aborted write.
    */
  private def pruneOldRuns(dir: os.Path): Unit =
    try
      val runs = runsNewestFirst(dir)
      val kept = keptRunIds(runs)
      for
        (id, files) <- runs if !kept.contains(id)
        file <- files
      do
        try os.remove(file)
        catch case NonFatal(_) => ()
    catch case NonFatal(_) => ()

  /** A run's id and the files it left in `.orca/cache/runs/`. */
  private type Run = (String, Seq[os.Path])

  /** Runs newest first. Run ids sort chronologically as strings (fixed-width
    * epoch prefix); a run with only one of its two files is still one run.
    */
  private def runsNewestFirst(dir: os.Path): List[Run] =
    os.list(dir)
      .groupBy(runIdOf)
      .toList
      .collect { case (Some(id), files) => (id, files.toSeq) }
      .sortBy((id, _) => id)
      .reverse

  /** Two kept sets: the newest [[MaxKeptRuns]] runs that own a manifest, and
    * the newest [[MaxKeptRuns]] runs of any kind.
    *
    * The first is what keeps the shell's "continue a session" list full.
    * Manifest-less runs are common — every fresh run spends tokens naming its
    * branch (`BranchNamingStrategy.shortenPrompt`) before its first stage, so
    * one cancelled at the plan prompt leaves a cost log and nothing else — and
    * on the newest-first ranking alone [[MaxKeptRuns]] of them would evict
    * every continuable run.
    *
    * The second bounds a workdir that stops producing manifests altogether,
    * where the first set alone has nothing to rank against.
    */
  private def keptRunIds(newestFirst: List[Run]): Set[String] =
    def newest(runs: List[Run]): Set[String] =
      runs.take(MaxKeptRuns).map((id, _) => id).toSet
    newest(newestFirst.filter((_, files) => files.exists(isManifest))) ++
      newest(newestFirst)

  /** `ext == "json"` is also the shell's listing filter
    * (`ManifestReader.list`), so "owns a manifest" means "the shell can offer
    * this run's sessions".
    */
  private def isManifest(file: os.Path): Boolean = file.ext == "json"

  /** The run a file in `.orca/cache/runs/` belongs to, or `None` for anything
    * this writer didn't produce (a leftover temp file, a stray edit).
    */
  private def runIdOf(file: os.Path): Option[String] =
    val name = file.last
    if name.endsWith("-cost.jsonl") then
      Some(name.dropRight("-cost.jsonl".length))
    else if name.endsWith(".json") then Some(name.dropRight(".json".length))
    else None

/** How a manifest session was opened. `Durable` when the event carries the name
  * an `agent.session(name, seed)` call minted it under, `OneShot` otherwise.
  */
private enum SessionKind(val wireValue: String):
  case Durable extends SessionKind(RunManifest.KindDurable)
  case OneShot extends SessionKind(RunManifest.KindOneShot)

private object SessionKind:
  def of(sessionName: Option[String]): SessionKind =
    if sessionName.isDefined then Durable else OneShot
