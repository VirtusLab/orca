package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaArgs
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchResult, LaunchedFlow}

/** Runs a resolved flow (ADR 0021 §2). Callers resolve `flow` and build its
  * `OrcaArgs`.
  */
private[shell] object RunAction:

  /** The flow's own args, built once by the caller, plus what to do when the
    * forced-version run fails to compile.
    */
  case class RunOptions(args: OrcaArgs, fallback: FallbackPolicy)

  /** Runs `flow` in `workDir` through `launch` — [[FlowLauncher.runAnnounced]]
    * in production.
    */
  def run(
      flow: DiscoveredFlow,
      opts: RunOptions,
      workDir: os.Path,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  ): LaunchResult =
    launch(
      opts.fallback,
      LaunchedFlow.of(flow),
      opts.args,
      workDir,
      terminal
    )
