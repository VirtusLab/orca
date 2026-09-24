package orca.runner

import orca.{BranchNamingStrategy, ConfigHome, OrcaArgs, StackSettings}
import orca.backend.Interaction
import orca.events.{OrcaListener, PricingTable}
import orca.progress.FlowSource

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
