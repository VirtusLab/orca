package orca.shell

import orca.OrcaDir
import orca.tools.Worktrees

/** Where the shell's per-redraw scans look: the checkout it was started in, and
  * the worktrees orca itself created for that repository (`--worktree` runs
  * live in one, and leave their progress log and session manifests there).
  *
  * The two are kept apart rather than concatenated: `ManifestReader` reads the
  * shell's own directory strictly and the rest guarded, and a plain list would
  * leave that difference to list position.
  */
private[shell] case class ScanDirs(own: os.Path, worktrees: List[os.Path]):
  /** Every directory to scan, for a consumer that treats them all alike. */
  def all: List[os.Path] = own :: worktrees

/** Resolution happens here, at the call site, rather than inside the scans
  * themselves — the convention `Cli`'s scaladoc states: the scans take explicit
  * directories, so their tests can seed bare temp dirs with no git repository
  * in sight.
  */
private[shell] object WorktreeScan:

  /** How many worktrees are scanned besides the shell's own directory. Orca
    * never removes a worktree and makes one per distinct task, so
    * `.orca/worktrees/` grows for the life of the repository — while these
    * scans run on every menu redraw. Matches `RunManifestWriter`'s own
    * kept-runs budget.
    *
    * A worktree past the cap is invisible to both scans: its sessions do not
    * reach `continue`, and an interrupted run in it is not offered. Ranked by
    * when each last recorded a run, so what drops out is what nobody has
    * touched in the last twenty tasks.
    */
  private[shell] val MaxScannedWorktrees = 20

  /** `workDir` plus the most recently used worktrees orca made FOR `workDir` —
    * the registered worktrees directly under its own `.orca/worktrees/` —
    * capped at [[MaxScannedWorktrees]].
    *
    * Scoped to one checkout because that is how the data is stored: `.orca/` is
    * per-checkout, and a worktree's runs leave their progress log and session
    * manifests in the worktree, not in the checkout that created it. So the
    * checkout that made the worktrees sees itself and all of them, while a
    * worktree — whose own `.orca/worktrees/` is empty — sees only itself. To
    * survey every run in the repository, run the shell from the checkout the
    * worktrees hang off.
    *
    * Only `workDir` when git has nothing to say (not a repository, git
    * unavailable): the shell's own directory is always worth scanning.
    *
    * Deliberately NOT every worktree git reports. A progress log is committed
    * repo content, so a worktree checked out to review someone else's branch
    * carries that branch's log — and its `userPrompt` is the task text the
    * resume offer would hand an agent verbatim.
    */
  def dirs(workDir: os.Path): ScanDirs =
    val root = OrcaDir.worktreesPath(workDir)
    ScanDirs(
      workDir,
      Worktrees
        .list(workDir)
        .filter(_ / os.up == root)
        // Decorate-sort-undecorate: `sortBy` re-evaluates its key on every
        // comparison, and this key is a syscall per call.
        .map(dir => (lastRunAt(dir), dir))
        .sortBy(-_._1)
        .take(MaxScannedWorktrees)
        .map(_._2)
    )

  /** When a worktree last recorded a run — the mtime of its
    * `.orca/cache/runs/`, which `RunManifestWriter` creates as it starts and
    * rewrites per manifest. `0` for a worktree that never ran one, and for one
    * that just went away: this runs on every menu redraw, over directories a
    * `git worktree remove` in another terminal can delete mid-scan, and every
    * step downstream of it survives that. Both answers sort last.
    */
  private def lastRunAt(worktree: os.Path): Long =
    scala.util.Try(os.mtime(OrcaDir.runsPath(worktree))).getOrElse(0L)
