package orca.shell.resume

import orca.OrcaDir
import orca.progress.{ProgressHeader, ProgressStore}

/** An unfinished flow run, byte-identically relaunchable: the flow script's
  * filename and the exact task text that started it (ADR 0021 §3 amendment).
  */
private[shell] case class InterruptedRun(flowName: String, userPrompt: String)

/** Detects an interrupted run for the main menu's "Resume interrupted run"
  * offer (ADR 0021 §3 amendment): a run that died mid-flight leaves its
  * `.orca/progress-<hash>.json` behind (failure teardown keeps the log and
  * stays on the branch; success teardown removes it in its final commit — ADR
  * 0018), so the log's mere presence on the current branch IS the detection
  * signal. No exit-code bookkeeping needed.
  */
private[shell] object ResumeDetector:

  /** The newest unfinished progress log's flow+task, or `None` when there is
    * nothing to offer: no `.orca` dir, no matching file, a symlinked file or
    * `.orca` itself (excluded rather than followed, the same committed-symlink
    * threat model other `.orca` reads guard against), a corrupt/unparseable
    * log, or a log whose header predates this feature (no recorded flow name
    * and/or task text — it still resumes on its own terms via the normal
    * fresh/resume path, per `ProgressLog`'s documented tolerant decoding; this
    * offer just doesn't apply to it) or was written by a run outside the shell
    * (`flowName` unrecorded — the simpler, honest choice over a partial
    * pick-the-flow-and-prefill-the-task fallback).
    *
    * Any failure here is silent, not surfaced as a menu warning: this runs on
    * every menu redraw (`Main.loop`), and a warning on each redraw would spam
    * the terminal for what is, at worst, a missed convenience.
    */
  def detect(workDir: os.Path): Option[InterruptedRun] =
    try
      newestProgressLogPath(workDir).flatMap: path =>
        ProgressStore.at(workDir, path).loadDetailed() match
          case ProgressStore.LoadResult.Loaded(log) => fromHeader(log.header)
          case _                                    => None
    catch case scala.util.control.NonFatal(_) => None

  private def fromHeader(header: ProgressHeader): Option[InterruptedRun] =
    for
      flowName <- header.flowName.filter(isBareFlowFilename)
      userPrompt <- header.userPrompt
    yield InterruptedRun(flowName, userPrompt)

  /** The header is committed repo content, and `flowName` later reaches
    * `FlowResolution.resolve`, which treats path-like refs as literal paths — a
    * forged `../x.sc` or absolute path would escape the flow tiers. A
    * legitimate header only ever holds `flow.last` (a bare `.sc` filename), so
    * anything else drops the offer.
    */
  private def isBareFlowFilename(name: String): Boolean =
    name.endsWith(".sc") && !name.contains('/') && !name.contains('\\') &&
      !name.startsWith("-") && !name.startsWith(".")

  /** Every `.orca/progress-<hash>.json` file, guarded like other `.orca` reads:
    * a symlinked `.orca` (or no `.orca` at all) yields no candidates, and an
    * individual symlinked log file is excluded rather than followed — a
    * committed symlink could otherwise redirect the read outside the working
    * tree. Picks the newest by mtime: with multiple unfinished logs (different
    * prompts, one branch — possible), only the newest is ever offered; nothing
    * else about the others is surfaced.
    */
  private def newestProgressLogPath(workDir: os.Path): Option[os.Path] =
    val root = OrcaDir.rootPath(workDir)
    if os.isLink(root) || !os.isDir(root) then None
    else
      os.list(root)
        .filter(p => !os.isLink(p) && isProgressLogName(p.last))
        .maxByOption(os.mtime(_))

  private def isProgressLogName(name: String): Boolean =
    name.matches("progress-[0-9a-f]{12}\\.json")
