package orca.lifecycle

import orca.RunTarget
import orca.gitref.BranchName
import orca.tools.PrReadiness

/** The parts of a run's `OrcaArgs` that [[FlowLifecycle.setup]] reads. */
private[orca] case class SetupRequest(
    userPrompt: String,
    target: RunTarget,
    branch: Option[BranchName],
    prReadiness: PrReadiness
)
