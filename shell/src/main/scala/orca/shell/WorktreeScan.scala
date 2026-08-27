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

  /** `workDir`, then every worktree under its repository's `.orca/worktrees/` —
    * deduplicated, since the shell may itself be running in one of them.
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
    workDir :: orcaMade.filterNot(_ == workDir)
