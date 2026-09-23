package orca.shell.menu

import org.jline.terminal.Terminal
import orca.{OrcaArgs, RunTarget, Uncommitted}
import orca.gitref.BranchName
import orca.progress.FeatureBranch
import orca.shell.ShellEnv
import orca.shell.actions.{FlowResolution, RunAction}
import orca.shell.resume.InterruptedRun
import orca.shell.run.FallbackPolicy
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}
import orca.util.TextUtil
import ox.discard

import scala.annotation.tailrec

/** The menu's Run a flow and Resume interrupted run items (ADR 0021 §2/§3). */
private[menu] object RunMenu:

  /** orca's flagship built-in flow — the run picker's default. */
  val FlagshipFlow = "implement.sc"

  /** Selects a flow, prompts for the task text, for where the run's work should
    * go ([[RunTarget]]) and, when that target creates a branch, for the branch
    * name, then hands off to `runAction` in the shell's `workDir`. Always
    * launches non-verbose.
    */
  def runFlow(ui: ShellUi, terminal: Terminal, runAction: RunFlowAction)(using
      env: ShellEnv
  ): Unit =
    for
      flow <- FlowPicker.listFlows.flatMap(flows =>
        FlowPicker.pickFlow(
          ui,
          "Run which flow?",
          flows,
          default = flows.find(_.name == FlagshipFlow)
        )
      )
      task <- promptTask(ui)
      target <- promptRunTarget(ui)
      branch <- promptBranchFor(ui, target).toOption
    do
      val args = OrcaArgs(
        userPrompt = task,
        verbose = false,
        target = target,
        branch = branch
      )
      val opts = RunAction.RunOptions(args, FallbackPolicy.Ask(ui))
      runAction(flow, opts, env.workDir, terminal).discard

  /** The run's `--branch` name, asked for only when `target` creates a branch.
    */
  private def promptBranchFor(
      ui: ShellUi,
      target: RunTarget
  ): UiOutcome[Option[BranchName]] =
    if target.skipBranch then UiOutcome.Selected(None)
    else promptBranchName(ui)

  /** Prompts for the run's branch name: `Selected(None)` on a blank answer (the
    * flow derives the name), re-asking on an invalid name.
    */
  @tailrec private def promptBranchName(
      ui: ShellUi
  ): UiOutcome[Option[BranchName]] =
    ui.input("Branch name (Enter to derive from the task)") match
      case UiOutcome.Cancelled => UiOutcome.Cancelled
      case UiOutcome.Selected(raw) if raw.trim.isEmpty =>
        UiOutcome.Selected(None)
      case UiOutcome.Selected(raw) =>
        FeatureBranch.parseRequested(raw) match
          case Left(message) =>
            ShellOutput.error(message)
            promptBranchName(ui)
          case Right(name) => UiOutcome.Selected(Some(name))

  /** Resumes `run` (ADR 0021 §3 amendment): relaunches its recorded flow with
    * the recorded task text verbatim — the progress log is keyed by a hash of
    * it — through the same path "Run a flow" uses. A catalog name is looked up
    * in the shell's catalog, where the run was launched from; the run itself
    * happens in `run.dir`, where its log is. The target is the default one: a
    * resumed log's header decides the branch.
    */
  def resumeInterruptedRun(
      ui: ShellUi,
      terminal: Terminal,
      run: InterruptedRun,
      runAction: RunFlowAction
  )(using ShellEnv): Unit =
    FlowResolution.resolveRecorded(run.flow) match
      case Left(message) =>
        ShellOutput.error(
          s"$message — to abandon the run: ${abandonCommand(run)}"
        )
      case Right(flow) =>
        val opts =
          RunAction.RunOptions(
            args = OrcaArgs(
              userPrompt = run.userPrompt,
              verbose = false,
              target = RunTarget.NewBranch(Uncommitted.Stash),
              branch = None
            ),
            fallback = FallbackPolicy.Ask(ui)
          )
        runAction(flow, opts, run.dir, terminal).discard

  /** Removes `run`'s progress log in a commit: the log is committed, so a plain
    * `rm` is undone by the next run's auto-stash restore.
    */
  private def abandonCommand(run: InterruptedRun): String =
    val git = s"git -C ${TextUtil.shellQuote(run.dir.toString)}"
    val log = TextUtil.shellQuote(run.log.relativeTo(run.dir).toString)
    s"$git rm $log && $git commit -m 'abandon orca run'"

  private def promptTask(ui: ShellUi): Option[String] =
    Prompts.nonBlankMultiline(
      ui,
      "Describe the task for this flow run",
      "task text can't be empty"
    )

  /** "Where should this run's work go?" — the run's destination as ONE choice
    * ([[RunTarget]]). `CurrentBranch` is skip-branch mode (ADR 0018 amendment):
    * the handoff-from-harness case, where the user already planned work on a
    * branch carrying plan files. `Worktree` is `--worktree`, which orca refuses
    * together with `--skip-branch` — one choice cannot express that pair, where
    * two independent confirms could.
    */
  def promptRunTarget(ui: ShellUi): Option[RunTarget] =
    ui.select(
      "Where should this run's work go?",
      MainMenu.runTargetChoices,
      default = Some(RunTarget.NewBranch(Uncommitted.Stash))
    ).toOption
