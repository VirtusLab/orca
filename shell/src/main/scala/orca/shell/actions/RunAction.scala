package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchResult}

/** Runs a resolved flow (ADR 0021 §2). The selection and task-text prompting
  * that produce `flow` and `task` live in `Main.runFlow`.
  */
private[shell] object RunAction:

  case class RunOptions(verbose: Boolean, fallback: FallbackPolicy)

  /** Runs `flow` as a tty-inherited child, printing the same start/end section
    * markers the menu always has — the announced-bracket + terminal handling
    * lives in [[FlowLauncher.runAnnounced]].
    */
  def run(
      flow: DiscoveredFlow,
      task: String,
      opts: RunOptions,
      workDir: os.Path,
      terminal: Terminal
  ): LaunchResult =
    FlowLauncher.runAnnounced(
      opts.fallback,
      flow.path,
      task,
      workDir,
      opts.verbose,
      terminal
    )
