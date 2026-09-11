package orca.runner

import orca.RunTarget
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
  /** The handoff for a run on `target` that happened in `worktree` and opened
    * `openedPr`:
    *
    *   - `NewBranch` — [[ReturnToStart]] when the run opened a PR: the work is
    *     on the PR, so the user is handed back the branch they were on. Without
    *     a PR the work is only on the feature branch, so HEAD stays there.
    *   - `CurrentBranch` (`--skip-branch`) — always [[StayPut]]: the run never
    *     left the user's branch, so it must not move them off it.
    *   - `Worktree` — always [[StayPut]]: the checkout is orca's own worktree,
    *     on its own branch, and the user's is untouched either way.
    *
    * `worktree` is checked independently of `target`, because a shell resume
    * relaunched without `--worktree` still runs inside one.
    *
    * The throwaway-branch delete is a separate decision, but it reads the same
    * PR: a feature branch holding nothing but orca's bookkeeping is deleted
    * (and HEAD moved back) when the run opened none, whatever the handoff says.
    * See `FlowLifecycle.finishBranch`.
    */
  def of(
      target: RunTarget,
      worktree: Option[os.Path],
      openedPr: Option[PrHandle]
  ): BranchHandoff =
    target match
      case RunTarget.NewBranch(_) =>
        if worktree.isEmpty && openedPr.isDefined then ReturnToStart
        else StayPut
      case RunTarget.CurrentBranch(_) | RunTarget.Worktree => StayPut
