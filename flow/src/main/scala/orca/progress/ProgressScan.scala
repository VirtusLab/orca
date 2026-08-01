package orca.progress

import orca.OrcaDir
import scala.util.control.NonFatal

/** A progress log found by [[ProgressScan.progressLogs]]: its path plus the
  * header it parsed cleanly to.
  */
case class ScannedProgressLog(path: os.Path, header: ProgressHeader)

/** Discovery of the progress logs present in a working directory, for callers
  * that must look at every in-flight run rather than one known prompt's log.
  *
  * Logs are prompt-keyed (`progress-<hash>.json`, [[ProgressStore.default]]),
  * so a repo can hold several at once — one per prompt still in flight. Reads
  * are guarded like other `.orca` reads: a symlinked (or missing) `.orca`
  * yields no candidates, and an individual symlinked log file is excluded
  * rather than followed, since a committed symlink could otherwise redirect the
  * read outside the working tree.
  */
object ProgressScan:

  /** Every `.orca/progress-<hash>.json` file under `workDir`, in `os.list`
    * order — callers impose their own ordering (e.g. newest by `os.mtime`).
    */
  def progressLogPaths(workDir: os.Path): List[os.Path] =
    val root = OrcaDir.rootPath(workDir)
    if os.isLink(root) || !os.isDir(root) then Nil
    else
      os.list(root)
        .iterator
        .filter(p => !os.isLink(p) && os.isFile(p) && isProgressLogName(p.last))
        .toList

  /** [[progressLogPaths]] with each log's header parsed. A corrupt log is
    * skipped silently: a scan reports the runs it can read, and one bad file
    * must cost only itself — callers reason about the logs they got back, so
    * dropping the whole list would read as "no runs in flight".
    */
  def progressLogs(workDir: os.Path): List[ScannedProgressLog] =
    progressLogPaths(workDir).flatMap: path =>
      // Defensive, not covered by a test: `load()` already folds unparseable
      // content into `None`, so only an IO failure (a file unreadable, or
      // deleted between the listing and the read) lands here — and it gets the
      // same per-log skip rather than sinking the scan.
      try
        ProgressStore
          .at(workDir, path)
          .load()
          .map(log => ScannedProgressLog(path, log.header))
      catch case NonFatal(_) => None

  private def isProgressLogName(name: String): Boolean =
    name.matches("progress-[0-9a-f]{12}\\.json")
