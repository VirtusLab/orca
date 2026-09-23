package orca.shell.menu

import orca.{OrcaArgs, RunTarget, Uncommitted}
import orca.discovery.Origin
import orca.progress.FlowSource
import orca.shell.TestShellEnv
import orca.shell.flows.DiscoveredFlow
import orca.shell.resume.InterruptedRun
import orca.shell.run.{LaunchResult, LaunchedFlow}
import orca.shell.ui.UiOutcome
import orca.testkit.{TempDirs, branchName}

import MenuFixtures.{captured, withDumbTerminal}

class RunMenuTest extends munit.FunSuite:

  // --- promptRunTarget (where the run's work goes, asked as one choice) ---

  private val newBranch = RunTarget.NewBranch(Uncommitted.Stash)

  test("promptRunTarget: the three destinations are offered, new branch first"):
    val ui = RecordingSelectUi(UiOutcome.Selected(newBranch))
    assertEquals(RunMenu.promptRunTarget(ui), Some(newBranch))
    assertEquals(
      ui.recordedChoices.head.map(_.value),
      List(
        newBranch,
        RunTarget.CurrentBranch(Uncommitted.Stash),
        RunTarget.Worktree
      )
    )

  test("promptRunTarget: cancelling aborts the run"):
    assertEquals(
      RunMenu.promptRunTarget(
        RecordingSelectUi[RunTarget](UiOutcome.Cancelled)
      ),
      None
    )

  // --- runFlow (the interactive launch path) ---

  test("runFlow: the flow picker starts on the flagship flow"):
    val ui = new RecordingSelectUi[DiscoveredFlow](UiOutcome.Cancelled)
    withDumbTerminal(
      RunMenu.runFlow(ui, _, (_, _, _, _, _) => LaunchResult.Ok)(using
        TestShellEnv()
      )
    )
    assertEquals(
      ui.recordedChoices.head.headOption.map(_.value.name),
      Some(RunMenu.FlagshipFlow)
    )

  /** Runs [[RunMenu.runFlow]] picking a flow, typing a task, then picking
    * `target` and answering the branch prompt from `branchAnswers`; returns the
    * UI and the args that reached the launcher.
    */
  private def runFlowWith(
      target: RunTarget,
      branchAnswers: List[UiOutcome[String]]
  ): (FlowScriptedUi, Option[OrcaArgs]) =
    val workDir = TempDirs.dir()
    val flowPath = workDir / ".orca" / "flows" / "run-flow.sc"
    os.write(flowPath, "// x\n", createFolders = true)
    val flow = DiscoveredFlow(
      name = "run-flow.sc",
      description = None,
      origin = Origin.Project,
      path = flowPath,
      shadows = Nil,
      source = FlowSource.Catalog("run-flow.sc")
    )
    val ui = FlowScriptedUi(
      selectScript = List(UiOutcome.Selected(flow), UiOutcome.Selected(target)),
      inputMultilineScript = List(UiOutcome.Selected("do the thing")),
      inputScript = branchAnswers
    )
    var recorded: Option[OrcaArgs] = None
    withDumbTerminal: terminal =>
      RunMenu.runFlow(
        ui,
        terminal,
        launch = (_, _, args, _, _) =>
          recorded = Some(args)
          LaunchResult.Ok
      )(using TestShellEnv(workDir))
    (ui, recorded)

  test("runFlow: a typed branch name reaches the launcher's args"):
    val (_, args) =
      runFlowWith(RunTarget.Worktree, List(UiOutcome.Selected("feature/x")))
    assertEquals(args.map(_.target), Some(RunTarget.Worktree))
    assertEquals(args.flatMap(_.branch).map(_.value), Some("feature/x"))

  test("runFlow: an invalid branch name is re-asked and the next one used"):
    val (ui, args) = runFlowWith(
      RunTarget.NewBranch(Uncommitted.Stash),
      List(UiOutcome.Selected("bad name"), UiOutcome.Selected("good-name"))
    )
    assertEquals(ui.inputCount, 2)
    assertEquals(args.flatMap(_.branch).map(_.value), Some("good-name"))

  test("runFlow: Enter at the branch prompt lets the flow derive the name"):
    val target = RunTarget.NewBranch(Uncommitted.Stash)
    val (_, args) = runFlowWith(target, List(UiOutcome.Selected("")))
    assertEquals(
      args,
      Some(
        OrcaArgs(
          userPrompt = "do the thing",
          verbose = false,
          target = target,
          branch = None
        )
      )
    )

  test("runFlow: cancelling the branch prompt aborts the run"):
    val (ui, args) = runFlowWith(RunTarget.Worktree, List(UiOutcome.Cancelled))
    assertEquals(ui.inputCount, 1)
    assertEquals(args, None)

  test("runFlow: the current-branch target asks no branch name"):
    val (ui, args) =
      runFlowWith(RunTarget.CurrentBranch(Uncommitted.Stash), Nil)
    assertEquals(ui.inputCount, 0)
    assertEquals(args.map(_.branch), Some(None))

  // --- resumeInterruptedRun ---
  //
  // `runAction` is injected (AuthorAction-style seam) so these never spawn a
  // real `scala-cli` subprocess; the recorded call's flow+task is what the
  // resume offer promises: byte-identical to what's stored on `InterruptedRun`.

  private def interrupted(flow: FlowSource, dir: os.Path): InterruptedRun =
    InterruptedRun(
      flow = flow,
      userPrompt = "fix the flaky test\nwith detail",
      branch = branchName("feat/x"),
      dir = dir,
      log = dir / ".orca" / "runs" / "k.progress.json"
    )

  /** Resumes `run` from `shellDir`, returning the launch it made, if any. */
  private def resumed(
      run: InterruptedRun,
      shellDir: os.Path
  ): Option[(LaunchedFlow, OrcaArgs, os.Path)] =
    var recorded: Option[(LaunchedFlow, OrcaArgs, os.Path)] = None
    withDumbTerminal: terminal =>
      RunMenu.resumeInterruptedRun(
        FlowScriptedUi(),
        terminal,
        run,
        launch = (_, flow, args, dir, _) =>
          recorded = Some((flow, args, dir))
          LaunchResult.Ok
      )(using TestShellEnv(shellDir))
    recorded

  private def projectFlow(dir: os.Path, name: String): os.Path =
    val path = dir / ".orca" / "flows" / name
    os.write(path, "// x\n", createFolders = true)
    path

  test(
    "resumeInterruptedRun: launches with the recorded task, verbatim"
  ):
    val workDir = TempDirs.dir()
    val _ = projectFlow(workDir, "resume-flow.sc")
    val launch = resumed(
      interrupted(FlowSource.Catalog("resume-flow.sc"), workDir),
      workDir
    )
    assertEquals(
      launch.map(_._2.userPrompt),
      Some("fix the flaky test\nwith detail")
    )

  test(
    "resumeInterruptedRun: the run happens in the directory its log was found in, with the default target"
  ):
    // A log found in an orca worktree resumes THERE, with no --worktree flag:
    // the flag would re-derive a path, this runs where the log actually is.
    val shellDir = TempDirs.dir()
    val worktree = TempDirs.dir()
    val _ = projectFlow(shellDir, "resume-flow.sc")
    val launch = resumed(
      interrupted(FlowSource.Catalog("resume-flow.sc"), worktree),
      shellDir
    )
    assertEquals(
      launch.map((_, args, dir) => (dir, args.target)),
      Some(worktree -> RunTarget.NewBranch(Uncommitted.Stash))
    )

  test(
    "resumeInterruptedRun: a catalog name is looked up in the shell's checkout, not the log's"
  ):
    // A worktree's project flows can lag the checkout that launched the run.
    val shellDir = TempDirs.dir()
    val worktree = TempDirs.dir()
    val shellFlow = projectFlow(shellDir, "resume-flow.sc")
    val _ = projectFlow(worktree, "resume-flow.sc")
    val launch = resumed(
      interrupted(FlowSource.Catalog("resume-flow.sc"), worktree),
      shellDir
    )
    assertEquals(launch.map(_._1.path), Some(shellFlow))

  test(
    "resumeInterruptedRun: a recorded file runs as is, even when the catalog has a flow of the same name"
  ):
    val workDir = TempDirs.dir()
    val _ = projectFlow(workDir, "implement.sc")
    val scratch = TempDirs.dir() / "implement.sc"
    os.write(scratch, "// scratch\n")
    val launch = resumed(
      interrupted(FlowSource.File(scratch.toString), workDir),
      workDir
    )
    assertEquals(launch.map(_._1.path), Some(scratch))

  test(
    "resumeInterruptedRun: a recorded file that no longer exists reports an error and never launches"
  ):
    val workDir = TempDirs.dir()
    val gone = (TempDirs.dir() / "gone.sc").toString
    val out = captured(
      assertEquals(
        resumed(interrupted(FlowSource.File(gone), workDir), workDir),
        None
      )
    )
    assert(out.contains(gone), out)
    assert(
      out.contains(s"git -C '$workDir' rm '.orca/runs/k.progress.json'"),
      out
    )

  test(
    "resumeInterruptedRun: an unresolvable flow name reports an error and never launches"
  ):
    val workDir = TempDirs.dir()
    val out = captured(
      assertEquals(
        resumed(
          interrupted(FlowSource.Catalog("no-such-flow.sc"), workDir),
          workDir
        ),
        None
      )
    )
    assert(out.contains("no-such-flow.sc"), out)
