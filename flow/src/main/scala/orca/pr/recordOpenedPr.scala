package orca.pr

import orca.FlowControl
import orca.tools.PrHandle

/** Tell the run's lifecycle that `pr` was opened. A run that created its own
  * branch and opened a PR hands the checkout back on the branch it started from
  * — the work is on the PR. A worktree run and a `--skip-branch` run never
  * change what is checked out.
  *
  * Call it OUTSIDE the stage that created the PR: a resumed run replays a
  * stage's recorded result without running its body, so a call from inside
  * would be skipped and the lifecycle would not learn the PR exists.
  *
  * Only a flow that opens its PR with a bare `gh.createPr` needs this;
  * [[openPrFromBranch]] and [[openPrIfGitHub]] record the handle themselves.
  */
def recordOpenedPr(pr: PrHandle)(using control: FlowControl): Unit =
  control.recordOpenedPr(pr)
