package orca.pr

import orca.{FlowControl, OrcaFlowException, OutsideStage}
import orca.tools.PrHandle

/** Tell the run's lifecycle that `pr` was opened. A run that created its own
  * branch and opened a PR hands the checkout back on the branch it started from
  * — the work is on the PR. A worktree run and a `--skip-branch` run never
  * change what is checked out.
  *
  * Callable only outside a stage — after the stage that created the PR returns:
  * a resumed run replays a stage's recorded result without running its body, so
  * a call from inside would be skipped and the lifecycle would not learn the PR
  * exists. [[OutsideStage]] rejects the direct in-stage call at compile time;
  * the runtime check catches a FlowControl-only helper invoked from within a
  * stage.
  *
  * Only a flow that opens its PR with a bare `gh.createPr` needs this;
  * [[openPrFromBranch]] and [[openPrIfGitHub]] record the handle themselves.
  */
def recordOpenedPr(pr: PrHandle)(using
    control: FlowControl,
    outside: OutsideStage
): Unit =
  if control.inStage then
    throw new OrcaFlowException(
      "recordOpenedPr(...) must be called outside a stage: return the handle " +
        "from the stage that opened the PR and record it after that stage " +
        "returns."
    )
  control.recordOpenedPr(pr)
