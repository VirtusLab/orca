package orca

import orca.tools.PrHandle

/** Per-run record of the pull request a flow opened, mixed into every
  * [[FlowControl]] — production and test doubles alike — so a double cannot
  * drift from production semantics.
  *
  * Read at teardown to decide where the run leaves the checkout —
  * `orca.runner.BranchHandoff` owns that rule.
  *
  * Recorded rather than inferred: a flow can also open its PR with a bare
  * `gh.createPr`, and then says so through [[orca.pr.recordOpenedPr]].
  *
  * Thread-affine like [[StageFrames]]: one `FlowControl` per top-level
  * `flow(...)`, single-threaded (R12, ADR 0018 §2.2). The plain var is safe
  * because [[StageFrames.assertOwnerThread]] enforces that — a
  * [[recordOpenedPr]] from an `ox.fork` must fail loudly rather than write a
  * handle the teardown read may never see.
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
  private[orca] def openedPr: Option[PrHandle] =
    assertOwnerThread("openedPr")
    opened
