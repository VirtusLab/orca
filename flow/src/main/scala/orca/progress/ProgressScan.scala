package orca.progress

import orca.OrcaDir
import orca.util.JsonFile

/** A progress log found by [[ProgressScan.progressLogs]]: its path plus the
  * header it parsed cleanly to.
  */
case class ScannedProgressLog(path: os.Path, header: ProgressHeader)

/** Discovery of the progress logs present in a working directory, for callers
  * that must look at every in-flight run rather than one known prompt's log.
  *
  * Logs are run-keyed (`.orca/runs/<key>.progress.json`,
  * [[ProgressStore.default]]), so a repo can hold several at once — one per
  * prompt still in flight. Reads are guarded like other `.orca` reads: a
  * symlinked (or missing) `.orca` or `.orca/runs` yields no candidates, and an
  * individual symlinked log file is excluded rather than followed, since a
  * committed symlink could otherwise redirect the read outside the working
  * tree.
  */
object ProgressScan:

  /** Every `<key>.progress.json` file under `<workDir>/.orca/runs/`, in
    * `os.list` order — callers impose their own ordering (e.g. newest by
    * `os.mtime`).
    */
  def progressLogPaths(workDir: os.Path): List[os.Path] =
    val root = OrcaDir.rootPath(workDir)
    val runs = OrcaDir.runsPath(workDir)
    if os.isLink(root) || !os.isDir(root) || os.isLink(runs) || !os.isDir(runs)
    then Nil
    else
      os.list(runs)
        .iterator
        .filter(p => !os.isLink(p) && os.isFile(p) && OrcaDir.isProgressLog(p))
        .toList

  /** [[progressLogPaths]] with each log's header parsed. A log that does not
    * load — corrupt, or unreadable — is skipped silently: a scan reports the
    * runs it can read, and one bad file must cost only itself; callers reason
    * about the logs they got back, so dropping the whole list would read as "no
    * runs in flight".
    */
  def progressLogs(workDir: os.Path): List[ScannedProgressLog] =
    progressLogPaths(workDir).flatMap: path =>
      JsonFile.read[ProgressLog](path) match
        case JsonFile.Read.Loaded(log) =>
          Some(ScannedProgressLog(path, log.header))
        case _ => None
