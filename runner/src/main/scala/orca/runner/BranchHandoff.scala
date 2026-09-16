package orca.runner

import orca.progress.BranchMode
import orca.tools.PrHandle

/** Where a successful run leaves HEAD, derived from the run rather than asked
  * for by the flow script — see [[BranchHandoff.of]] for the rule.
  */
private[runner] enum BranchHandoff:
  /** Check the branch the run started on back out, keeping the feature branch.
    */
  case ReturnToStart

  /** Leave HEAD where the run ended — on the feature branch. */
  case StayPut

private[runner] object BranchHandoff:
  /** The handoff for a run bound in `branchMode` that happened in `worktree`
    * and opened `openedPr`:
    *
    *   - `Created` — [[ReturnToStart]] when the run opened a PR: the work is on
    *     the PR, so the user is handed back the branch they were on. Without a
    *     PR the work is only on the feature branch, so HEAD stays there.
    *   - `Reused` (`--skip-branch`) — always [[StayPut]]: the run never left
    *     the user's branch, so it must not move them off it.
    *   - inside a worktree — always [[StayPut]]: the checkout is orca's own
    *     worktree, on its own branch, and the user's is untouched either way.
    *
    * `branchMode` is the run's, read from its progress header, so a resume
    * relaunched with different flags still hands off the way the run was bound;
    * `worktree` is checked on its own for the same reason — a shell resume
    * relaunched without `--worktree` still runs inside one.
    *
    * The throwaway-branch delete is a separate decision that reads the same PR
    * — see `FlowLifecycle.finishBranch`.
    */
  def of(
      branchMode: BranchMode,
      worktree: Option[os.Path],
      openedPr: Option[PrHandle]
  ): BranchHandoff =
    branchMode match
      case BranchMode.Created =>
        if worktree.isEmpty && openedPr.isDefined then ReturnToStart
        else StayPut
      case BranchMode.Reused => StayPut
