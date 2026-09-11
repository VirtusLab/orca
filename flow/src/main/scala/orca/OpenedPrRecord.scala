package orca

import orca.tools.PrHandle

/** Per-run record of the pull request a flow opened, shared by every
  * [[FlowControl]] implementation (production
  * [[orca.runner.DefaultFlowContext]] and the test doubles), so a test double
  * cannot drift from production semantics.
  *
  * The lifecycle will read it at teardown to decide where to leave the
  * checkout: a new-branch run that opened a PR has somewhere to send the user,
  * so it returns to the branch it started from. A worktree run and a
  * `--skip-branch` run never change what is checked out, whether or not a PR
  * was opened.
  *
  * Recorded rather than inferred, because a flow can open its PR through
  * [[orca.pr.openPrFromBranch]], through [[orca.pr.openPrIfGitHub]], or with a
  * bare `gh.createPr` — the last of which tells the lifecycle by calling
  * [[orca.pr.recordOpenedPr]].
  *
  * Thread-affine like [[StageFrames]]: one `FlowControl` per top-level
  * `flow(...)`, single-threaded (R12, ADR 0018 §2.2). The var is plain because
  * [[StageFrames.assertOwnerThread]] enforces that — [[recordOpenedPr]] is
  * public API for flow scripts, so a call from an `ox.fork` has to fail loudly
  * rather than write a handle the teardown read may never see.
  */
private[orca] trait OpenedPrRecord:
  this: StageFrames =>

  private var opened: Option[PrHandle] = None

  /** Record `pr` as the PR this run opened. Last write wins: a flow that opens
    * a tentative PR and reopens or replaces it reports the latest one.
    */
  private[orca] def recordOpenedPr(pr: PrHandle): Unit =
    assertOwnerThread("recordOpenedPr(...)")
    opened = Some(pr)

  /** The PR this run opened, or `None` when it opened none. */
  private[orca] def openedPr: Option[PrHandle] = opened
