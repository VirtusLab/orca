package orca.pr

import orca.{FlowControl, OrcaFlowException, OutsideStage}
import orca.tools.PrHandle

/** Tell the run's lifecycle that `pr` was opened; its teardown reads that to
  * decide where the run leaves the checkout.
  *
  * Callable only outside a stage — after the stage that created the PR
  * returns: a resumed run replays a stage's recorded result without running
  * its body, so a call from inside would be skipped and the lifecycle would
  * never learn the PR exists.
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
