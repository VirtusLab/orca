package orca.shell.menu

import org.jline.terminal.Terminal
import orca.shell.actions.RunAction
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.LaunchResult

/** Opens `path` in the user's editor and returns its exit code —
  * `EditAction.editInPlace` in production.
  */
private[menu] type SpawnEditor = (Terminal, os.Path) => Int

/** Runs a resolved flow in a directory — [[RunAction.run]] over
  * `FlowLauncher.runAnnounced` in production.
  */
private[menu] type RunFlowAction =
  (DiscoveredFlow, RunAction.RunOptions, os.Path, Terminal) => LaunchResult
