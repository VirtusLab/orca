package orca.runlog

import orca.{OrcaDir, RunKey}

import scala.util.control.NonFatal

/** Bounds `.orca/cache/runs/` by run count and each run directory by trace
  * count, and removes the cache files older versions wrote (ADR 0025). Runs at
  * attempt start. Each delete is guarded on its own, so a file a concurrent
  * cleanup got to first does not stop the rest.
  */
private[orca] object RunPruning:

  /** How many of the newest runs, and of the newest runs that recorded a
    * session, are kept; also how many attempts' trace logs a run directory
    * keeps. Resumable runs are kept regardless.
    */
  val MaxKept: Int = 20

  /** Deletes the run directories under `cacheRuns` outside the kept sets. Kept:
    * the `current` run's, every run with a session minted after its last
    * success (it may still be resumed), the newest [[MaxKept]] runs that
    * recorded a session, and the newest [[MaxKept]] of any kind. Newest is by
    * the modification time of the run's event log; a run without one is oldest.
    *
    * The session-recording set keeps the shell's continue list full, as
    * session-less runs (one cancelled at the plan prompt) are common.
    */
  def pruneRuns(cacheRuns: os.Path, current: RunKey): Unit =
    val runs = runsNewestFirst(cacheRuns, current)
    val kept = keptRuns(runs)
    runs.filterNot(kept.contains).foreach(run => removeGuarded(run.dir))

  /** Deletes the trace logs, rolled parts included, of every attempt in
    * `runDir` but the newest [[MaxKept]] by attempt id.
    */
  def pruneTraces(runDir: os.Path): Unit =
    val byAttempt = listed(runDir)
      .filter(os.isFile(_, followLinks = false))
      .groupBy(OrcaDir.attemptIdOf)
      .collect:
        case (Some(id), files) => id -> files
    val kept = byAttempt.keys.toList.sortBy(_.value).reverse.take(MaxKept).toSet
    for
      (id, files) <- byAttempt if !kept.contains(id)
      file <- files
    do removeGuarded(file)

  /** Deletes what older versions kept under `cache` (`.orca/cache`): the
    * `attempts/` directory and the `<key>.sessions.json` session records in
    * `runs/`.
    */
  def removeLegacy(cache: os.Path): Unit =
    removeGuarded(cache / "attempts")
    listed(cache / "runs")
      .filter(f =>
        f.last.endsWith(".sessions.json") && os.isFile(f, followLinks = false)
      )
      .foreach(removeGuarded)

  /** `modifiedAt` is the event log's modification time, `None` without one. */
  private case class Run(
      dir: os.Path,
      modifiedAt: Option[Long],
      sessions: Sessions
  )

  private enum Sessions:
    case NoneRecorded, Recorded, Resumable

  private def runsNewestFirst(cacheRuns: os.Path, current: RunKey): List[Run] =
    listed(cacheRuns)
      .filter(d => os.isDir(d, followLinks = false) && d.last != current.value)
      .map(runOf)
      .sortBy(_.modifiedAt)(using Ordering[Option[Long]].reverse)

  private def keptRuns(newestFirst: List[Run]): Set[Run] =
    newestFirst.filter(_.sessions == Sessions.Resumable).toSet ++
      newestFirst.filter(_.sessions != Sessions.NoneRecorded).take(MaxKept) ++
      newestFirst.take(MaxKept)

  private def runOf(dir: os.Path): Run =
    val log = dir / OrcaDir.EventLogName
    val events = RunEventReader.readOnly(
      log,
      Set(classOf[RunEvent.SessionMinted], classOf[RunEvent.RunSucceeded])
    )
    val sessions =
      if SessionProjection.records(events).nonEmpty then Sessions.Resumable
      else if events.exists(_.isInstanceOf[RunEvent.SessionMinted]) then
        Sessions.Recorded
      else Sessions.NoneRecorded
    Run(dir, Option.when(os.isFile(log))(os.mtime(log)), sessions)

  private def listed(dir: os.Path): List[os.Path] =
    try if os.isDir(dir) then os.list(dir).toList else Nil
    catch case NonFatal(_) => Nil

  private def removeGuarded(path: os.Path): Unit =
    try os.remove.all(path)
    catch case NonFatal(_) => ()
