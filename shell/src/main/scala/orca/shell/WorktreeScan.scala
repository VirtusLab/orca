package orca.shell

import orca.OrcaDir
import orca.tools.Worktrees

/** The directories the shell's per-redraw scans look in: the checkout it was
  * started in, plus the worktrees orca itself created for that repository
  * (`--worktree` runs live in one, and leave their progress log and session
  * manifests there).
  *
  * Resolution happens here, at the call site, rather than inside the scans
  * themselves — the convention `Cli`'s scaladoc states: the scans take explicit
  * directories, so their tests can seed bare temp dirs with no git repository
  * in sight.
  */
private[shell] object WorktreeScan:

  /** How many worktrees are scanned besides `workDir`. Orca never removes a
    * worktree and makes one per distinct task, so `.orca/worktrees/` grows for
    * the life of the repository — while these scans run on every menu redraw.
    * Matches `RunManifestWriter`'s own kept-runs budget.
    */
  private val MaxScannedWorktrees = 20

  /** `workDir`, then the most recently used worktrees under its repository's
    * `.orca/worktrees/` — deduplicated, since the shell may itself be running
    * in one of them, and capped at [[MaxScannedWorktrees]], newest first by
    * when each last recorded a run. A stat per worktree, not a read of its
    * contents; a worktree nobody has run in for that long has no session anyone
    * is about to continue.
    *
    * Just `workDir` when git has nothing to say (not a repository, git
    * unavailable): the shell's own directory is always worth scanning.
    *
    * Deliberately NOT every worktree git reports. A progress log is committed
    * repo content, so a worktree checked out to review someone else's branch
    * carries that branch's log — and its `userPrompt` is the task text the
    * resume offer would hand an agent verbatim. Only the trees orca made for
    * this repository are its own runs.
    */
  def dirs(workDir: os.Path): List[os.Path] =
    val orcaMade = Worktrees
      .mainCheckout(workDir)
      .map(OrcaDir.worktreesPath)
      .toList
      .flatMap(root => Worktrees.list(workDir).filter(_ / os.up == root))
    workDir :: orcaMade
      .filterNot(_ == workDir)
      .sortBy(lastRunAt)
      .reverse
      .take(MaxScannedWorktrees)

  /** When a worktree last recorded a run — the mtime of its
    * `.orca/cache/runs/`, which `RunManifestWriter` creates as it starts and
    * rewrites per manifest. `0` for a worktree that never ran one, or whose
    * directory can't be read, which sorts it last rather than dropping it
    * outright.
    */
  private def lastRunAt(worktree: os.Path): Long =
    scala.util.Try(os.mtime(OrcaDir.runsPath(worktree))).getOrElse(0L)
