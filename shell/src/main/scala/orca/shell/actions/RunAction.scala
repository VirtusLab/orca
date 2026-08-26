package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}

/** Runs a resolved flow (ADR 0021 §2). The selection and task-text prompting
  * that produce `flow` and `task` live in `Main.runFlow`.
  */
private[shell] object RunAction:

  /** The flow's own argv flags, built once by the caller, plus what to do when
    * the forced-version run fails to compile.
    */
  case class RunOptions(flags: FlowFlags, fallback: FallbackPolicy)

  /** Runs `flow` as a tty-inherited child, printing the same start/end section
    * markers the menu always has — the announced-bracket + terminal handling
    * lives in [[FlowLauncher.runAnnounced]]. `launch` is injectable,
    * [[AuthorAction]]-style, so a test can assert on what reaches the launcher
    * instead of spawning a real subprocess.
    */
  def run(
      flow: DiscoveredFlow,
      task: String,
      opts: RunOptions,
      workDir: os.Path,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    launch(opts.fallback, flow.path, task, workDir, opts.flags, terminal)
