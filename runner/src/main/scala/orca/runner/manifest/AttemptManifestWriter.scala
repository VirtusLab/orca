package orca.runner.manifest

import orca.{AttemptId, OrcaDir}
import orca.util.JsonFile
import orca.events.{OrcaEvent, OrcaListener}
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.time.Instant
import scala.util.control.NonFatal

/** The manifest's outcome at finish. Narrower than [[AttemptStatus]] on
  * purpose: [[AttemptStatus.Running]] is the state an attempt starts in, never
  * a finish input.
  */
private[orca] enum AttemptOutcome:
  case Succeeded, Failed

/** Always-attached listener (like [[LoggingListener]]) that writes the attempt
  * manifest ([[AttemptManifest]], ADR 0021 §8) to
  * `.orca/cache/attempts/<AttemptId>.manifest.json`. The file exists from the
  * writer's construction and is rewritten whole, atomically, on every stage
  * transition and `SessionCommitted`, so a crashed attempt still leaves its
  * sessions on disk with `status: "Running"` and a dead `pid` — the shell
  * treats that as "crashed, but still offers its sessions". Also appends the
  * sibling `<AttemptId>.cost.jsonl` ([[CostLog]]) as turns spend tokens.
  *
  * `flowName` comes from `ORCA_FLOW_NAME`, set by the shell before exec'ing the
  * flow subprocess (`FlowLauncher.childEnv`); `runFlow` never sees the `.sc`
  * path itself, so a flow launched outside the shell leaves it unset and the
  * manifest's `flow` is `None`.
  *
  * Thread-safety is covered on [[AttemptManifestWriterState]], which owns the
  * actual mutable state.
  */
private[orca] trait AttemptManifestWriter extends OrcaListener:
  /** Finalizes the manifest: `status` and `finishedAt`, then a last write.
    * Called once from `flow()`'s `finally`.
    */
  def finish(outcome: AttemptOutcome): Unit

private[orca] object AttemptManifestWriter:

  /** Build a production writer whose state is owned by an Ox actor in the given
    * scope (mirrors [[orca.runner.terminal.TerminalOutput.start]]). The actor
    * fork lives as long as the scope, which must span construction through
    * `finish`; `flow()` provides that scope. `pid` is this process's, recorded
    * for the shell's liveness check and part of the [[AttemptId]].
    */
  def start(
      workDir: os.Path,
      orcaVersion: String,
      flowName: Option[String],
      pid: Long,
      clock: () => Instant
  )(using Ox, BufferCapacity): AttemptManifestWriter =
    val state =
      new AttemptManifestWriterState(workDir, orcaVersion, flowName, pid, clock)
    new ActorAttemptManifestWriter(Actor.create(state))

/** Actor-backed [[AttemptManifestWriter]]. `onEvent` is a `tell`; `finish` is
  * an `ask` so its final write completes before the caller proceeds. A throw
  * from a `tell`'s handler would close the actor's channel — so the state
  * guards every write internally and neither entry point ever throws.
  */
private class ActorAttemptManifestWriter(
    actor: ActorRef[AttemptManifestWriterState]
) extends AttemptManifestWriter:
  def onEvent(event: OrcaEvent): Unit = actor.tell(_.onEvent(event))
  def finish(outcome: AttemptOutcome): Unit = actor.ask(_.finish(outcome))

/** Mutable manifest-building state — not thread-safe in isolation.
  * [[ActorAttemptManifestWriter]] serialises every call onto one actor thread:
  * `onEvent` is a `tell` (fire-and-forget, though a full mailbox blocks the
  * emitter — every event writes, but turns arrive seconds apart and an append
  * is one small write, so the queue still drains far faster than it fills) and
  * `finish` is an `ask` (its write must land before `flow()` moves on to the
  * cost summary). Every write is guarded internally ([[safeWrite]]) so a
  * transient failure can't quarantine the writer or throw out of a `tell`'s
  * handler. Tests construct this directly and drive events synchronously.
  *
  * Construction writes the first manifest and prunes the attempts directory
  * once ([[AttemptPruning]]); the cost log is created by its first append.
  */
private[runner] class AttemptManifestWriterState(
    workDir: os.Path,
    orcaVersion: String,
    flowName: Option[String],
    pid: Long,
    clock: () => Instant
) extends AttemptManifestWriter:

  private val log = LoggerFactory.getLogger("orca.flow")

  private val startedAt: Instant = clock()
  private val attemptId: AttemptId = AttemptId(startedAt, pid)
  private val attemptsDir: os.Path = OrcaDir.ensureAttempts(workDir)
  private val manifestPath: os.Path = OrcaDir.manifestPath(workDir, attemptId)
  private val costLog: CostLog = CostLog(
    OrcaDir.costLogPath(workDir, attemptId)
  )

  private var state = ManifestState.initial

  safeWrite()
  guarded("attempt pruning")(AttemptPruning.prune(attemptsDir))

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      state = state.entered(name)
      safeWrite()
    case OrcaEvent.StageCompleted(_) =>
      state.exited match
        case Some(next) => state = next
        case None =>
          log.warn(
            "unbalanced StageCompleted: stage stack already empty, ignoring"
          )
      safeWrite()
    case e: OrcaEvent.SessionCommitted =>
      state = state.withSession(e, clock())
      safeWrite()
    case t: OrcaEvent.TokensUsed =>
      guarded("cost log append"):
        costLog.append(CostRecord.of(t, clock(), state.currentStage))
    case _ => ()

  def finish(outcome: AttemptOutcome): Unit =
    state = state.finished(outcome, clock())
    safeWrite()

  /** The whole-file rewrite, guarded. Because it rewrites everything, a
    * swallowed failure self-heals on the next successful write — which the cost
    * log's appends do not (see [[CostLog]]).
    */
  private def safeWrite(): Unit = guarded("attempt manifest write")(write())

  /** Runs an IO step so a transient failure (e.g. ENOSPC) is logged and
    * swallowed rather than escaping. Both files need this and for the same
    * reason: a throw from a `tell`'s handler closes the actor's channel and
    * quarantines the writer for the rest of the attempt, and a throw from
    * `finish` would surface into teardown.
    */
  private def guarded(what: String)(op: => Unit): Unit =
    try op
    catch case NonFatal(e) => log.warn(s"$what failed (best-effort)", e)

  private def write(): Unit =
    val (status, finishedAt) = state.phase match
      case Phase.Running               => (AttemptStatus.Running, None)
      case Phase.Finished(outcome, at) => (statusOf(outcome), Some(at))
    JsonFile.write(
      manifestPath,
      manifestPath / os.up,
      AttemptManifest(
        orcaVersion = orcaVersion,
        flow = flowName,
        workDir = workDir.toString,
        pid = pid,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status,
        sessions = state.entries.map(_.session)
      )
    )

  private def statusOf(finished: AttemptOutcome): AttemptStatus =
    finished match
      case AttemptOutcome.Succeeded => AttemptStatus.Succeeded
      case AttemptOutcome.Failed    => AttemptStatus.Failed

/** A tracked session plus its conversation key (wireId-or-clientId); with
  * `session.harness` that is the event's dedup key
  * (`OrcaEvent.SessionCommitted`). Kept here because the manifest never carries
  * the raw `clientId`.
  */
private case class SessionEntry(
    conversationKey: String,
    session: ManifestSession
)

/** Whether the attempt has finished, and how. */
private enum Phase:
  case Running
  case Finished(outcome: AttemptOutcome, at: Instant)

/** What the manifest is projected from; every transition is a pure step. */
private case class ManifestState(
    stageStack: List[String],
    entries: List[SessionEntry],
    phase: Phase
):
  /** The innermost open stage. */
  def currentStage: Option[String] = stageStack.headOption

  def entered(stage: String): ManifestState =
    copy(stageStack = stage :: stageStack)

  /** `None` when no stage is open. */
  def exited: Option[ManifestState] = stageStack match
    case Nil       => None
    case _ :: rest => Some(copy(stageStack = rest))

  /** Upsert by `(harness, conversationKey)`: the same session re-firing
    * `SessionCommitted` on a later turn (retries, resumed durable calls)
    * updates `stage`/`lastActiveAt` in place (last-write-wins). The minted key
    * is kept once seen, because a chat turn continuing the same durable session
    * carries none.
    */
  def withSession(
      event: OrcaEvent.SessionCommitted,
      now: Instant
  ): ManifestState =
    val conversationKey =
      OrcaEvent.conversationKey(event.clientId, event.wireId)
    val idx = entries.indexWhere(e =>
      e.session.harness == event.harness && e.conversationKey == conversationKey
    )
    val existing = entries.lift(idx)
    val entry = SessionEntry(
      conversationKey,
      ManifestSession(
        harness = event.harness,
        wireId = event.wireId,
        agent = event.agent,
        role = event.role,
        stage = currentStage,
        minted = event.sessionKey.orElse(existing.flatMap(_.session.minted)),
        lastActiveAt = now
      )
    )
    copy(entries =
      if idx >= 0 then entries.updated(idx, entry) else entries :+ entry
    )

  def finished(outcome: AttemptOutcome, at: Instant): ManifestState =
    copy(phase = Phase.Finished(outcome, at))

private object ManifestState:
  val initial: ManifestState = ManifestState(Nil, Nil, Phase.Running)
