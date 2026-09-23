package orca.runner

import orca.{FlowContext, FlowControl, StageFrames}
import orca.gitref.CommitHash
import orca.progress.ProgressStore
import orca.sessions.SessionStore

/** Production FlowControl wiring over the run's [[DefaultFlowContext]]. Like
  * the context, constructed by `runFlow` after lifecycle setup has run.
  *
  * Stage-identity bookkeeping (withStage, claimSessionKey) and the per-run turn
  * claim come from the shared [[StageFrames]] mixin, so test doubles cannot
  * drift from production.
  */
private[orca] class DefaultFlowControl(
    val context: FlowContext,
    val progressStore: ProgressStore,
    val sessionStore: SessionStore,
    /** The commit the run started from (see
      * [[orca.FlowControl.startingCommit]]) — resolved by `FlowLifecycle.setup`
      * before the control exists, so it arrives frozen.
      */
    private[orca] val startingCommit: Option[CommitHash]
) extends FlowControl,
      StageFrames
