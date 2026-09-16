package orca.pr

import orca.{FlowControl, WorkspaceWrite}
import orca.progress.PublishedWork
import orca.tools.PrHandle

/** Record `pr` as this run's [[PublishedWork]]; only its URL is kept.
  *
  * Call it inside the stage that opened the PR, so the stage's commit carries
  * the record and a resumed run reads it back without re-running the body. A
  * flow cannot mint [[WorkspaceWrite]] outside a stage body, which is what
  * places the call.
  *
  * Only a flow that opens its PR with a bare `gh.createPr` needs this;
  * [[openPrFromBranch]] and [[openPrIfGitHub]] record the handle themselves.
  */
def recordOpenedPr(pr: PrHandle)(using
    control: FlowControl,
    w: WorkspaceWrite
): Unit =
  // The write is a file read-modify-write with no other runtime guard on
  // `WorkspaceWrite`, so a call from a fork must fail here.
  control.assertOwnerThread("recordOpenedPr(...)")
  control.progressStore.recordPublished(PublishedWork(pr.url))
