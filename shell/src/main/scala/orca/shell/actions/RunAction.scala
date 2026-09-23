package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaArgs
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchResult}

/** Runs a resolved flow (ADR 0021 §2). Callers resolve `flow` and build its
  * `OrcaArgs`.
  */
private[shell] object RunAction:

  /** The flow's own args, built once by the caller, plus what to do when the
    * forced-version run fails to compile.
    */
  case class RunOptions(args: OrcaArgs, fallback: FallbackPolicy)

  /** Runs `flow` as a tty-inherited child, printing the same start/end section
    * markers the menu always has — the announced-bracket + terminal handling
    * lives in [[FlowLauncher.runAnnounced]]. `launch` is injectable,
    * [[AuthorAction]]-style, so a test can assert on what reaches the launcher
    * instead of spawning a real subprocess.
    */
  def run(
      flow: DiscoveredFlow,
      opts: RunOptions,
      workDir: os.Path,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    launch(opts.fallback, flow.path, opts.args, workDir, terminal)
