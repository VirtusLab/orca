package orca.shell.menu

import org.jline.terminal.Terminal
import orca.{OrcaArgs, RunTarget, Uncommitted}
import orca.gitref.BranchName
import orca.progress.FeatureBranch
import orca.shell.ShellEnv
import orca.shell.actions.FlowResolution
import orca.shell.resume.InterruptedRun
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchedFlow}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import orca.util.TextUtil
import ox.discard

import scala.annotation.tailrec

/** The menu's Run a flow and Resume interrupted run items (ADR 0021 §2/§3). */
private[menu] object RunMenu:

  /** orca's flagship built-in flow — the run picker's default. */
  val FlagshipFlow = "implement.sc"

  /** A new branch in this checkout, stashing uncommitted files: the run target
    * prompt's default, and where a resumed run goes (its log's header decides
    * the branch).
    */
  private val DefaultRunTarget: RunTarget =
    RunTarget.NewBranch(Uncommitted.Stash)

  /** Where a run's work goes, offered as one choice on one axis rather than a
    * branch confirm followed by a worktree confirm: the answers are not
    * independent — orca refuses `--worktree` with `--skip-branch` — and asking
    * separately would leave prompt order to prevent a pair [[RunTarget]] has no
    * case for. The menu never keeps uncommitted files, so every row stashes.
    */
  private val runTargetChoices: List[Choice[RunTarget]] = List(
    Choice(DefaultRunTarget, "A new branch in this checkout"),
    Choice(
      RunTarget.CurrentBranch(Uncommitted.Stash),
      "The branch checked out now — the flow commits onto it"
    ),
    Choice(
      RunTarget.Worktree,
      "A new worktree — a separate checkout under .orca/worktrees/, " +
        "leaving this one untouched"
    )
  )

  /** Selects a flow, prompts for the task text, for where the run's work should
    * go ([[RunTarget]]) and, when that target creates a branch, for the branch
    * name, then launches it in the shell's `workDir` through `launch`
    * ([[FlowLauncher.runAnnounced]] in production). Always launches
    * non-verbose.
    */
  def runFlow(
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using env: ShellEnv): Unit =
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
      launch(
        FallbackPolicy.Ask(ui),
        LaunchedFlow.of(flow),
        args,
        env.workDir,
        terminal
      ).discard

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
    * happens in `run.dir`, where its log is.
    */
  def resumeInterruptedRun(
      ui: ShellUi,
      terminal: Terminal,
      run: InterruptedRun,
      launch: FlowLauncher.FlowLaunch
  )(using ShellEnv): Unit =
    FlowResolution.resolveRecorded(run.flow) match
      case Left(message) =>
        ShellOutput.error(
          s"$message — to abandon the run: ${abandonCommand(run)}"
        )
      case Right(flow) =>
        val args = OrcaArgs(
          userPrompt = run.userPrompt,
          verbose = false,
          target = DefaultRunTarget,
          branch = None
        )
        launch(
          FallbackPolicy.Ask(ui),
          LaunchedFlow.of(flow),
          args,
          run.dir,
          terminal
        ).discard

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
      runTargetChoices,
      default = Some(DefaultRunTarget)
    ).toOption
