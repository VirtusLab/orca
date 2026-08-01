package orca.progress

import orca.OrcaDir

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

  /** Every `.orca/progress-<hash>.json` under `workDir`, in `os.list` order —
    * callers impose their own ordering (e.g. newest by `os.mtime`).
    */
  def progressLogPaths(workDir: os.Path): List[os.Path] =
    val root = OrcaDir.rootPath(workDir)
    if os.isLink(root) || !os.isDir(root) then Nil
    else
      os.list(root)
        .iterator
        .filter(p => !os.isLink(p) && isProgressLogName(p.last))
        .toList

  private def isProgressLogName(name: String): Boolean =
    name.matches("progress-[0-9a-f]{12}\\.json")
