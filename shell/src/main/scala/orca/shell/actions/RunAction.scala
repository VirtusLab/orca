package orca.shell.actions

import org.jline.terminal.Terminal
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{
  ChildTerminal,
  FallbackPolicy,
  FlowLauncher,
  LaunchResult
}
import orca.shell.ui.ShellOutput

/** Runs a resolved flow (ADR 0021 §2) — the moved action half of
  * `Main.runFlow`; the selection and task-text prompting that produce `flow`
  * and `task` stay in `Main`.
  */
private[shell] object RunAction:

  case class RunOptions(verbose: Boolean, fallback: FallbackPolicy)

  /** Runs `flow` as a tty-inherited child under [[ChildTerminal.withChild]]
    * (ADR 0021 §2), printing the same start/end section markers the menu always
    * has.
    */
  def run(
      flow: DiscoveredFlow,
      task: String,
      opts: RunOptions,
      workDir: os.Path,
      terminal: Terminal
  ): LaunchResult =
    println()
    ShellOutput.section(s"starting flow ${flow.name}")
    val result = ChildTerminal.withChild(terminal)(
      FlowLauncher.run(opts.fallback, flow.path, task, workDir, opts.verbose)
    )
    ShellOutput.section(
      s"flow ${flow.name} ${FlowLauncher.outcomeSuffix(result)}"
    )
    println()
    result
