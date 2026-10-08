package orca.runner

import orca.{BranchNamingStrategy, ConfigHome, OrcaArgs, StackSettings}
import orca.backend.Interaction
import orca.events.{OrcaListener, PricingTable}
import orca.progress.FlowSource
import orca.runlog.RunEventLog
import ox.Ox

/** Everything one `flow(...)` attempt was asked to do, built once by `flow` and
  * handed to `runFlow`. `workDir` is where the attempt runs (a `--worktree`
  * run's checkout, not the caller's directory).
  */
private[orca] case class RunRequest(
    args: OrcaArgs,
    workDir: os.Path,
    // `None` builds the terminal UI.
    interaction: Option[Interaction],
    // Beyond the interaction's own listeners.
    extraListeners: List[OrcaListener],
    wiring: FlowWiring,
    pricing: PricingTable,
    // Starts the attempt's event log, also the run's session store, in the
    // given scope. `runFlow` calls it once it holds the working tree's lock.
    startRunLog: Ox => RunEventLog,
    setup: SetupOptions
)

/** The part of a [[RunRequest]] read after the agents and tools are built: role
  * resolution, reviewer discovery and `FlowLifecycle.setup`.
  */
private[orca] case class SetupOptions(
    branchNaming: Option[BranchNamingStrategy],
    // Wins over the project file's stack keys and skips discovery.
    stackSettings: Option[StackSettings],
    roles: RoleOverrides,
    configHome: ConfigHome,
    // Recorded in a freshly-written progress header.
    flowSource: Option[FlowSource]
)
