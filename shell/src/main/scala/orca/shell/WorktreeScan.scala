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

  /** `workDir` plus the most recently used worktrees under its repository's
    * `.orca/worktrees/` — deduplicated, since the shell may itself be running
    * in one of them, and capped at [[MaxScannedWorktrees]].
    *
    * Only `workDir` when git has nothing to say (not a repository, git
    * unavailable): the shell's own directory is always worth scanning.
    *
    * Deliberately NOT every worktree git reports. A progress log is committed
    * repo content, so a worktree checked out to review someone else's branch
    * carries that branch's log — and its `userPrompt` is the task text the
    * resume offer would hand an agent verbatim. Only the trees orca made for
    * this repository are its own runs.
    */
  def dirs(workDir: os.Path): ScanDirs =
    // One `git worktree list` for both the main-checkout derivation and the
    // filter below: `mainCheckout` falls back to the list whenever `workDir` is
    // itself a linked worktree, which would otherwise spawn git twice.
    val worktrees = Worktrees.list(workDir)
    val orcaMade = Worktrees
      .mainCheckout(workDir, worktrees)
      .map(OrcaDir.worktreesPath)
      .fold(List.empty[os.Path])(root => worktrees.filter(_ / os.up == root))
    ScanDirs(
      workDir,
      orcaMade
        .filterNot(_ == workDir)
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
