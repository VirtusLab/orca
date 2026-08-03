package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import orca.OrcaDir
import orca.agents.JsonData
import orca.events.{OrcaEvent, OrcaListener, PriceList, Pricing}
import orca.progress.ProgressLog
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
      pricing: PriceList,
      clock: () => Instant
  )(using Ox, BufferCapacity): RunManifestWriter =
    val state =
      new RunManifestWriterState(workDir, orcaVersion, flowName, pricing, clock)
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
  * emitter — turns arrive seconds apart and only stage/session events write, so
  * the queue drains far faster than it fills) and `finish` is an `ask` (its
  * write must land before `flow()` moves on to the cost summary). Every write
  * is guarded internally ([[safeWrite]]) so a transient failure can't
  * quarantine the writer or throw out of a `tell`'s handler. Tests construct
  * this directly and drive events synchronously.
  *
  * The manifest file only comes into existence on the first `SessionCommitted`
  * — earlier stage and token events just update the in-memory stage stack and
  * cost accumulator (so the first session is stamped with the right stage, and
  * no token spend is lost) — and `finish()` no-ops if none ever committed: a
  * session-less run offers nothing to continue (ADR 0021 §8).
  */
private[runner] class RunManifestWriterState(
    workDir: os.Path,
    orcaVersion: String,
    flowName: Option[String],
    pricing: PriceList,
    clock: () => Instant
) extends RunManifestWriter:

  private val log = LoggerFactory.getLogger("orca.flow")

  /** How many of the newest manifests `pruneOldManifests` keeps. */
  private val MaxKeptManifests = 20

  private val pid: Long = ProcessHandle.current().pid()
  private val startedAt: Instant = clock()
  private val manifestPath: os.Path =
    OrcaDir.cacheRunsPath(workDir) / s"${startedAt.toEpochMilli}-$pid.json"

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
      cost: CostAccumulator = CostAccumulator(),
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
    case OrcaEvent.SessionCommitted(harness, clientId, wireId, agent, role) =>
      state = state.copy(entries =
        upsertSession(harness, clientId, wireId, agent, role)
      )
      safeWrite()
    // Accumulate only: a turn fires far more often than a stage or session
    // event, and every write rewrites the whole file.
    case OrcaEvent.TokensUsed(agent, model, usage, role, attempt) =>
      val turn = ManifestTurn(
        at = clock().toString,
        agent = agent,
        role = role,
        stage = state.stageStack.headOption,
        promptTokens = usage.inputTokens,
        attempt = attempt
      )
      state = state.copy(cost =
        state.cost
          .record(turn, usage, Pricing.resolve(pricing.table, model, usage))
      )
    case _ => ()

  /** Whether a `SessionCommitted` has ever landed — the manifest file's
    * existence gate. `state.entries` only ever grows or upserts in place (never
    * shrinks), so this is equivalent to "at least one session was committed so
    * far" without a separate flag.
    */
  private def hasCommittedSession: Boolean = state.entries.nonEmpty

  def finish(outcome: RunOutcome): Unit =
    state = state.copy(outcome = outcomeOf(outcome), finishedAt = Some(clock()))
    if hasCommittedSession then safeWrite()

  /** `write()` guarded so a transient failure (e.g. ENOSPC) is logged and
    * swallowed rather than escaping: a throw from a `tell`'s handler would
    * close the actor's channel and quarantine the writer for the rest of the
    * run, and a throw from `finish` would surface into run teardown. The
    * manifest is observability, not something a flow should fail over — and one
    * failed write must not stop the next one from succeeding.
    */
  private def safeWrite(): Unit =
    try write()
    catch
      case NonFatal(e) =>
        log.warn("run manifest write failed (best-effort)", e)

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
      agent: String,
      role: Option[String]
  ): List[Entry] =
    val key = wireId.getOrElse(clientId)
    val now = clock().toString
    val stage = state.stageStack.headOption
    val sessionName = durableSessionName(clientId)
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

  /** `clientId` joined against every `progress-*.json` under `.orca/` —
    * `SessionRecord`s only exist for durable `agent.session(name, seed)`
    * sessions, so a match means `clientId` came from one; the record's `name`
    * becomes the manifest's `sessionName`. `None` for a one-shot call, and
    * currently also for an interactive one — see [[SessionKind]]'s scaladoc.
    */
  private def durableSessionName(clientId: String): Option[String] =
    progressLogFiles.iterator
      .flatMap: path =>
        try
          readFromString[ProgressLog](os.read(path))(using
            progressLogCodec
          ).sessions
            .find(_.id == clientId)
            .map(_.name)
        catch case NonFatal(_) => None
      .nextOption()

  private val progressLogCodec = summon[JsonData[ProgressLog]].codec

  private def progressLogFiles: List[os.Path] =
    val root = OrcaDir.rootPath(workDir)
    if os.exists(root) then
      os.list(root)
        .filter(p => p.last.startsWith("progress-") && p.last.endsWith(".json"))
        .toList
    else Nil

  /** Atomic rewrite of the whole manifest — the `ProgressStore.writeLog`
    * temp+move idiom: a sibling temp file, then `os.move(atomicMove = true)` so
    * a crash mid-write never leaves a torn file behind. On the very first
    * write, also prunes `.orca/cache/runs/` down to its newest
    * [[MaxKeptManifests]] files (ADR 0021 §8) — every later write only rewrites
    * this run's own file, so no new file is ever added afterward and re-pruning
    * would find nothing to do.
    */
  private def write(): Unit =
    val manifest = RunManifest(
      manifestVersion = RunManifest.SupportedVersion,
      orcaVersion = orcaVersion,
      flow = flowName,
      workDir = workDir.toString,
      pid = pid,
      startedAt = startedAt.toString,
      finishedAt = state.finishedAt.map(_.toString),
      outcome = state.outcome.wireValue,
      sessions = state.entries.map(_.session),
      cost = state.cost.summarise,
      turns = state.cost.turns.toList
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
    if !state.prunedOnce then
      state = state.copy(prunedOnce = true)
      pruneOldManifests(dir)

  /** Keeps the newest [[MaxKeptManifests]] (by filename, which sorts
    * chronologically since `<startedAt-epoch-ms>-<pid>.json` epoch prefixes are
    * fixed-width), deleting the rest. Fully best-effort — the listing itself
    * and each delete are both guarded — since this runs exactly once per writer
    * and a failure here (a vanished dir, a concurrent cleanup) must not turn
    * into a quarantined listener or an aborted manifest write.
    */
  private def pruneOldManifests(dir: os.Path): Unit =
    try
      val newestFirst =
        os.list(dir).filter(_.ext == "json").sortBy(_.last).reverse
      newestFirst
        .drop(MaxKeptManifests)
        .foreach: p =>
          try os.remove(p)
          catch case NonFatal(_) => ()
    catch case NonFatal(_) => ()

/** How a manifest session was opened. `Durable` when the writer joins
  * `clientId` to a `SessionRecord` in the progress log (an `agent.session(...)`
  * call), `OneShot` otherwise. `Interactive` is reserved and currently unused:
  * `SessionCommitted` carries nothing that distinguishes an interactive call
  * from an autonomous one, so interactive calls land as `OneShot` today.
  */
private enum SessionKind(val wireValue: String):
  case Durable extends SessionKind(RunManifest.KindDurable)
  case OneShot extends SessionKind(RunManifest.KindOneShot)
  case Interactive extends SessionKind(RunManifest.KindInteractive)

private object SessionKind:
  def of(sessionName: Option[String]): SessionKind =
    if sessionName.isDefined then Durable else OneShot
