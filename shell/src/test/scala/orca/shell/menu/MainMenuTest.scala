package orca.shell.menu

import orca.progress.FlowSource
import orca.shell.resume.InterruptedRun
import orca.testkit.branchName

class MainMenuTest extends munit.FunSuite:

  test(
    "choices(Some(count)) yields the 10 ADR-order items, all enabled"
  ):
    val values = MainMenu
      .choices(continueSessionCount = Some(2), None, os.root)
      .map(_.value)
    assertEquals(
      values,
      List(
        MenuItem.RunFlow,
        MenuItem.ViewFlow,
        MenuItem.EditFlow,
        MenuItem.CreateFlow,
        MenuItem.ForkFlow,
        MenuItem.ContinueSession,
        MenuItem.Reconfigure,
        MenuItem.EditSettings,
        MenuItem.RediscoverStack,
        MenuItem.Exit
      )
    )
    assert(
      MainMenu
        .choices(continueSessionCount = Some(2), None, os.root)
        .forall(_.disabledReason.isEmpty)
    )

  test("choices(None) has no ContinueSession item"):
    val choices = MainMenu.choices(continueSessionCount = None, None, os.root)
    assert(!choices.exists(_.value == MenuItem.ContinueSession))

  test(
    "choices(Some(count)) labels ContinueSession with the newest run's session count"
  ):
    val choices =
      MainMenu.choices(continueSessionCount = Some(3), None, os.root)
    val label =
      choices.find(_.value == MenuItem.ContinueSession).get.label
    assertEquals(
      label,
      "Continue a session from the last attempt with sessions (3 session(s))"
    )

  test(
    "EditFlow/CreateFlow/ForkFlow labels name both hand and agent modes"
  ):
    val choices = MainMenu
      .choices(continueSessionCount = None, None, os.root)
    val byValue = choices.map(c => c.value -> c.label).toMap
    assertEquals(
      byValue(MenuItem.EditFlow),
      "Edit a flow — by hand, or an agent makes the changes"
    )
    assertEquals(
      byValue(MenuItem.CreateFlow),
      "Create a new flow — by hand, or an agent writes it"
    )
    assertEquals(
      byValue(MenuItem.ForkFlow),
      "Fork a flow — by hand, or an agent adapts the copy"
    )

  test(
    "Reconfigure/RediscoverStack labels say what they reconfigure/re-detect"
  ):
    val choices = MainMenu
      .choices(continueSessionCount = None, None, os.root)
    val byValue = choices.map(c => c.value -> c.label).toMap
    assertEquals(
      byValue(MenuItem.Reconfigure),
      "Re-configure — pick the agents & models for planning/coding/review"
    )
    assertEquals(
      byValue(MenuItem.RediscoverStack),
      "Clear stack settings (format/lint/test) — re-detected on the next flow run"
    )

  test("choices(resumeOffer = None) has no ResumeRun item"):
    val choices = MainMenu
      .choices(continueSessionCount = None, None, os.root)
    assert(!choices.exists(_.value == MenuItem.ResumeRun))

  test(
    "choices(resumeOffer = Some(...)) inserts ResumeRun right after RunFlow, labeled with flow, prompt, and branch"
  ):
    val run = InterruptedRun(
      flow = FlowSource.Catalog("implement.sc"),
      userPrompt = "fix the flaky integration test in the payments module",
      branch = branchName("feat/x"),
      dir = os.root / "work",
      log = os.root / "work" / "run.progress.json"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run),
      workDir = run.dir
    )
    assertEquals(
      choices.map(_.value).take(2),
      List(MenuItem.RunFlow, MenuItem.ResumeRun)
    )
    assertEquals(
      choices.find(_.value == MenuItem.ResumeRun).map(_.label),
      Some(
        "Resume interrupted run — implement.sc: fix the flaky integration test in the pa… on feat/x"
      )
    )

  test("the resume offer shows a recorded file's full path, unclipped"):
    val path = "/home/u/scratch/a/long/directory/name/for/the/flow/implement.sc"
    val run = InterruptedRun(
      flow = FlowSource.File(path),
      userPrompt = "fix it",
      branch = branchName("feat/x"),
      dir = os.root / "work",
      log = os.root / "work" / "run.progress.json"
    )
    val label = MainMenu
      .choices(
        continueSessionCount = None,
        resumeOffer = Some(run),
        workDir = run.dir
      )
      .find(_.value == MenuItem.ResumeRun)
      .map(_.label)
    assertEquals(
      label,
      Some(s"Resume interrupted run — $path: fix it on feat/x")
    )

  test("choices(resumeOffer = Some(...)) label drops control characters"):
    val run = InterruptedRun(
      flow = FlowSource.Catalog("fix.sc"),
      userPrompt = "safe\u001b[31m text\u0007",
      branch = branchName("feat/x"),
      dir = os.root / "work",
      log = os.root / "work" / "run.progress.json"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run),
      workDir = run.dir
    )
    val label = choices.find(_.value == MenuItem.ResumeRun).get.label
    assert(!label.exists(_.isControl), label)

  test(
    "choices(resumeOffer = Some(...)) label flattens a multi-line prompt"
  ):
    val run = InterruptedRun(
      flow = FlowSource.Catalog("fix.sc"),
      userPrompt = "line one\nline two",
      branch = branchName("feat/x"),
      dir = os.root / "work",
      log = os.root / "work" / "run.progress.json"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run),
      workDir = run.dir
    )
    assertEquals(
      choices.find(_.value == MenuItem.ResumeRun).map(_.label),
      Some("Resume interrupted run — fix.sc: line one line two on feat/x")
    )

  test(
    "choices(resumeOffer = Some(...)) leaves every other item's label alone"
  ):
    val withOffer = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(
        InterruptedRun(
          FlowSource.Catalog("a.sc"),
          "short task",
          branchName("feat/x"),
          dir = os.root / "work",
          log = os.root / "work" / "run.progress.json"
        )
      ),
      workDir = os.root
    )
    val without = MainMenu
      .choices(continueSessionCount = None, None, os.root)
    assertEquals(
      withOffer.filterNot(_.value == MenuItem.ResumeRun).map(_.label),
      without.map(_.label)
    )

  test("EditSettings label names both tiers it can open"):
    val choices = MainMenu
      .choices(continueSessionCount = None, None, os.root)
    val byValue = choices.map(c => c.value -> c.label).toMap
    assertEquals(
      byValue(MenuItem.EditSettings),
      "Edit settings — open the project or global settings file in your editor"
    )

  test("the resume offer names the worktree when the log is not in this tree"):
    val run = InterruptedRun(
      flow = FlowSource.Catalog("implement.sc"),
      userPrompt = "fix the flaky test",
      branch = branchName("feat/x"),
      dir = os.root / "repo" / ".orca" / "worktrees" / "ab12cd34",
      log =
        os.root / "repo" / ".orca" / "worktrees" / "ab12cd34" / "run.progress.json"
    )
    val label = MainMenu
      .choices(
        continueSessionCount = None,
        resumeOffer = Some(run),
        workDir = os.root / "repo"
      )
      .find(_.value == MenuItem.ResumeRun)
      .map(_.label)
    assert(label.exists(_.endsWith("(in ab12cd34)")), label.toString)
