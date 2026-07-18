package orca.runner

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import orca.OrcaDir
import orca.agents.JsonData
import orca.events.{OrcaEvent, OrcaListener}
import orca.progress.ProgressLog
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.util.control.NonFatal

/** Always-attached listener (like [[LoggingListener]]) that writes the
  * per-run session manifest ([[RunManifest]], ADR 0021 §8) to
  * `.orca/cache/runs/<startedAt-epoch-ms>-<pid>.json`. Rewrites the whole file
  * atomically (the `ProgressStore.writeLog` temp+move idiom,
  * `ProgressStore.scala:150-176`) on every stage transition and
  * `SessionCommitted`, so a crashed run still leaves its sessions on disk with
  * `outcome: "running"` and a dead `pid` — the shell treats that as "crashed,
  * but still offers its sessions".
  *
  * `flowName` comes from `ORCA_FLOW_NAME`, read by the caller (`flow()`), not
  * here — the flow script's own filename is genuinely unavailable inside the
  * library: `runFlow` never sees the `.sc` path, only the parsed `OrcaArgs`.
  * The shell sets the env var before exec'ing the flow subprocess (epic
  * 6/7); until then every manifest's `flow` is `None`.
  *
  * THREAD-SAFETY: [[OrcaListener]]s receive events from parallel agent forks.
  * Unlike [[orca.events.CostTracker]]'s lock-free `AtomicReference.updateAndGet`,
  * this listener's update is not a pure function of the old state — it ends in
  * a file write — so a CAS retry could write the file twice for one logical
  * event. `synchronized` around the whole read-modify-write-file sequence
  * keeps each event's effect atomic instead.
  *
  * The manifest file only comes into existence on the first `SessionCommitted`
  * — stage events before that update the in-memory stage stack (so the stage
  * a first session lands in is stamped correctly) but write nothing, and
  * `finish()` no-ops if no session ever committed: a run that never commits a
  * session leaves no manifest — a session-less run offers nothing to continue
  * (ADR 0021 §8).
  */
private[orca] class RunManifestWriter(
    workDir: os.Path,
    orcaVersion: String,
    flowName: Option[String],
    clock: () => Instant
) extends OrcaListener:

  private val log = LoggerFactory.getLogger("orca.flow")

  private val pid: Long = ProcessHandle.current().pid()
  private val startedAt: Instant = clock()
  private val manifestPath: os.Path =
    OrcaDir.cacheRunsPath(workDir) / s"${startedAt.toEpochMilli}-$pid.json"

  /** A tracked session plus the dedup key it was upserted under —
    * `(harness, wireId-or-clientId)`, per the event's dedup contract
    * (`OrcaEvent.SessionCommitted`'s scaladoc) — kept alongside the public
    * [[ManifestSession]] shape since the manifest itself never carries the
    * raw `clientId`.
    */
  private case class Entry(harness: String, dedupKey: String, session: ManifestSession)

  /** The manifest's `outcome` as an internal type rather than a bare string,
    * so a typo in a future call site fails to compile instead of landing on
    * disk; `wireValue` is the JSON string [[RunManifest.outcome]] carries.
    */
  private enum Outcome(val wireValue: String):
    case Running extends Outcome("running")
    case Succeeded extends Outcome("succeeded")
    case Failed extends Outcome("failed")

  private case class State(
      stageStack: List[String] = Nil,
      entries: List[Entry] = Nil,
      outcome: Outcome = Outcome.Running,
      finishedAt: Option[Instant] = None,
      prunedOnce: Boolean = false
  )

  private var state = State()

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name) =>
      synchronized:
        state = state.copy(stageStack = name :: state.stageStack)
        if hasCommittedSession then write()
    case OrcaEvent.StageCompleted(_) =>
      synchronized:
        state = state.copy(stageStack = state.stageStack.drop(1))
        if hasCommittedSession then write()
    case OrcaEvent.SessionCommitted(backend, clientId, wireId, agent, role) =>
      synchronized:
        state = state.copy(entries =
          upsertSession(backend, clientId, wireId, agent, role)
        )
        write()
    case _ => ()

  /** Whether a `SessionCommitted` has ever landed — the manifest file's
    * existence gate. `state.entries` only ever grows or upserts in place
    * (never shrinks), so this is equivalent to "at least one session was
    * committed so far" without a separate flag.
    */
  private def hasCommittedSession: Boolean = state.entries.nonEmpty

  /** Finalizes the manifest: `outcome` (`"succeeded"` or `"failed"`) and
    * `finishedAt`, then a last write. Called once from `flow()`'s `finally` —
    * OUTSIDE the `EventDispatcher`'s per-listener isolation (unlike
    * `onEvent`, nothing quarantines a throw from here), so a write failure is
    * logged and swallowed rather than escaping into run teardown: the
    * manifest is observability, not something a flow should fail over.
    *
    * No-ops (writes and creates nothing) if no `SessionCommitted` was ever
    * seen — see the class scaladoc.
    */
  def finish(outcome: String): Unit =
    val parsed = outcome match
      case "succeeded" => Outcome.Succeeded
      case "failed"    => Outcome.Failed
      case other =>
        throw new IllegalArgumentException(
          s"""RunManifestWriter.finish: outcome must be "succeeded" or "failed", got: $other"""
        )
    synchronized:
      state = state.copy(outcome = parsed, finishedAt = Some(clock()))
      if hasCommittedSession then
        try write()
        catch
          case NonFatal(e) =>
            log.warn("run manifest final write failed (best-effort)", e)

  /** Upsert-by-dedup-key (mirrors `ProgressStore`'s upsert idiom): the same
    * session re-firing `SessionCommitted` on a later turn (retries, resumed
    * durable calls) updates `stage`/`lastActiveAt`/`sessionName` in place
    * (last-write-wins), while `firstSeenAt` is preserved from the first
    * sighting.
    */
  private def upsertSession(
      backend: String,
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
      state.entries.find(e => e.harness == backend && e.dedupKey == key)
    val session = ManifestSession(
      harness = backend,
      wireId = wireId,
      resumable = wireId.isDefined,
      reason =
        if wireId.isEmpty then
          Some(s"$backend sessions do not survive the run")
        else None,
      agent = agent,
      role = role,
      stage = stage,
      sessionName = sessionName,
      kind = if sessionName.isDefined then "durable" else "oneShot",
      firstSeenAt = existing.map(_.session.firstSeenAt).getOrElse(now),
      lastActiveAt = now
    )
    val entry = Entry(backend, key, session)
    if existing.isDefined then
      state.entries.map: e =>
        if e.harness == backend && e.dedupKey == key then entry else e
    else state.entries :+ entry

  /** `clientId` joined against every `progress-*.json` under `.orca/` —
    * `SessionRecord`s only exist for durable `agent.session(name, seed)`
    * sessions (research 08 §A.4), so a match means `clientId` came from one;
    * the record's `name` is the manifest's `sessionName`. `None` for a plain
    * one-shot call — and, a known gap, for an interactive call too
    * (`AgentCall.runInteractiveOnce` mints a fresh `SessionId` that never
    * touches a `FlowSession`), so interactive sessions currently report
    * `kind: "oneShot"` (a gap for the ADR to absorb: `SessionCommitted`
    * carries nothing that distinguishes interactive from autonomous).
    */
  private def durableSessionName(clientId: String): Option[String] =
    progressLogFiles.iterator
      .flatMap: path =>
        try
          readFromString[ProgressLog](os.read(path))(using progressLogCodec)
            .sessions
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
    * temp+move idiom: a sibling temp file, then `os.move(atomicMove = true)`
    * so a crash mid-write never leaves a torn file behind. On the very first
    * write, also prunes `.orca/cache/runs/` down to its newest 20 files (ADR
    * 0021 §8) — every later write only rewrites this run's own file, so no
    * new file is ever added afterward and re-pruning would find nothing to
    * do.
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
    if !state.prunedOnce then
      state = state.copy(prunedOnce = true)
      pruneOldManifests(dir)

  /** Keeps the newest 20 manifests (by filename, which sorts chronologically
    * since `<startedAt-epoch-ms>-<pid>.json` epoch prefixes are fixed-width),
    * deleting the rest. Fully best-effort — the listing itself and each
    * delete are both guarded — since this runs exactly once per writer and a
    * failure here (a vanished dir, a concurrent cleanup) must not turn into a
    * quarantined listener or an aborted manifest write.
    */
  private def pruneOldManifests(dir: os.Path): Unit =
    try
      val newestFirst =
        os.list(dir).filter(_.ext == "json").sortBy(_.last).reverse
      newestFirst.drop(20).foreach: p =>
        try os.remove(p)
        catch case NonFatal(_) => ()
    catch case NonFatal(_) => ()
