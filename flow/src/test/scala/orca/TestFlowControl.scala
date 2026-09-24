package orca

import orca.gitref.CommitHash
import orca.progress.ProgressStore
import orca.sessions.SessionStore

/** A `FlowControl` over real stores, for exercising the `stage` runtime (commit
  * + resume).
  *
  * Stage-identity bookkeeping (withStage and claimSessionKey) and the per-run
  * turn claim are inherited from the shared `StageFrames` mixin — the SAME
  * implementation production uses, so this double can't diverge from production
  * nesting/resume semantics and greenwash a test.
  */
class TestFlowControl(
    val progressStore: ProgressStore,
    val sessionStore: SessionStore,
    private[orca] val startingCommit: Option[CommitHash] = None
) extends FlowControl,
      StageFrames
