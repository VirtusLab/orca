package orca.shell.resume

import orca.progress.{ProgressHeader, ProgressScan, ProgressStore}

/** An unfinished flow run, byte-identically relaunchable: the flow script's
  * filename and the exact task text that started it (ADR 0021 §3 amendment).
  */
private[shell] case class InterruptedRun(
    flowName: String,
    userPrompt: String,
    /** The directory the log was found in — the shell's own, or one of the
      * worktrees orca made. The relaunch runs THERE, which is what makes it a
      * resume rather than a fresh run: the log it resumes from is in that
      * directory and nowhere else.
      */
    dir: os.Path
)

/** Detects an interrupted run for the main menu's "Resume interrupted run"
  * offer (ADR 0021 §3 amendment): a run that died mid-flight leaves its
  * `.orca/progress-<hash>.json` behind (failure teardown keeps the log and
  * stays on the branch; success teardown removes it in its final commit — ADR
  * 0018), so the log's mere presence on the current branch IS the detection
  * signal. No exit-code bookkeeping needed.
  */
private[shell] object ResumeDetector:

  /** The newest unfinished progress log's flow+task, or `None` when there is
    * nothing to offer: nothing found by the scan (see
    * [[orca.progress.ProgressScan]] for what it skips), a corrupt/unparseable
    * log, or a log whose header predates this feature (no recorded flow name
    * and/or task text — it still resumes on its own terms via the normal
    * fresh/resume path, per `ProgressLog`'s documented tolerant decoding; this
    * offer just doesn't apply to it) or was written by a run outside the shell
    * (`flowName` unrecorded — the simpler, honest choice over a partial
    * pick-the-flow-and-prefill-the-task fallback).
    *
    * `dirs` are the directories to scan ([[orca.shell.WorktreeScan.dirs]] picks
    * them); the winner reports the one it was found in, so the caller can run
    * the resume where its log actually is. Git is never asked here — the
    * directories arrive resolved, which is what lets these scans be tested
    * against bare temp directories.
    *
    * Any failure here is silent, not surfaced as a menu warning: this runs on
    * every menu redraw (`Main.loop`), and a warning on each redraw would spam
    * the terminal for what is, at worst, a missed convenience.
    */
  def detect(dirs: List[os.Path]): Option[InterruptedRun] =
    try
      newestProgressLog(dirs).flatMap: found =>
        ProgressStore.at(found.dir, found.path).loadDetailed() match
          case ProgressStore.LoadResult.Loaded(log) =>
            fromHeader(log.header, found.dir)
          case _ => None
    catch case scala.util.control.NonFatal(_) => None

  private def fromHeader(
      header: ProgressHeader,
      dir: os.Path
  ): Option[InterruptedRun] =
    for
      flowName <- header.flowName.filter(isBareFlowFilename)
      userPrompt <- header.userPrompt
    yield InterruptedRun(flowName, userPrompt, dir)

  /** The header is committed repo content, and `flowName` later reaches
    * `FlowResolution.resolve`, which treats path-like refs as literal paths — a
    * forged `../x.sc` or absolute path would escape the flow tiers. A
    * legitimate header only ever holds `flow.last` (a bare `.sc` filename), so
    * anything else drops the offer.
    */
  private def isBareFlowFilename(name: String): Boolean =
    name.endsWith(".sc") && !name.contains('/') && !name.contains('\\') &&
      !name.startsWith("-") && !name.startsWith(".")

  /** A scanned progress log: which directory it was found in, and where. Named
    * rather than a pair, since both halves are `os.Path`.
    */
  private case class FoundLog(dir: os.Path, path: os.Path, mtime: Long)

  /** The newest by mtime of the scanned progress logs — one ordering across
    * every directory, not one winner per directory. With multiple unfinished
    * logs (different prompts, one branch, or one per worktree) only the newest
    * is ever offered; nothing else about the others is surfaced.
    */
  private def newestProgressLog(dirs: List[os.Path]): Option[FoundLog] =
    dirs.flatMap(logsIn).maxByOption(_.mtime)

  /** One directory's logs, or none — a directory the shell neither created nor
    * controls (an unreadable `.orca` in another worktree; a log another orca
    * process removed on success between the listing and the read) must cost
    * only itself, not the whole offer. Same rule `ProgressScan.progressLogs`
    * states for a single bad file.
    */
  private def logsIn(dir: os.Path): List[FoundLog] =
    try
      ProgressScan
        .progressLogPaths(dir)
        .flatMap: path =>
          scala.util
            .Try(os.mtime(path))
            .toOption
            .map(FoundLog(dir, path, _))
    catch case scala.util.control.NonFatal(_) => Nil
