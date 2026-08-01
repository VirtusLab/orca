package orca.shell

import orca.shell.resume.InterruptedRun

class MainMenuTest extends munit.FunSuite:

  test(
    "choices(Some(count)) yields the 10 ADR-order items, all enabled"
  ):
    val values = MainMenu
      .choices(continueSessionCount = Some(2))
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
        .choices(continueSessionCount = Some(2))
        .forall(_.disabledReason.isEmpty)
    )

  test("choices(None) has no ContinueSession item"):
    val choices = MainMenu.choices(continueSessionCount = None)
    assert(!choices.exists(_.value == MenuItem.ContinueSession))

  test(
    "choices(Some(count)) labels ContinueSession with the newest run's session count"
  ):
    val choices = MainMenu.choices(continueSessionCount = Some(3))
    val label =
      choices.find(_.value == MenuItem.ContinueSession).get.label
    assertEquals(
      label,
      "Continue a session from the last flow run (3 session(s))"
    )

  test(
    "EditFlow/CreateFlow/ForkFlow labels name both hand and agent modes"
  ):
    val choices = MainMenu
      .choices(continueSessionCount = None)
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

  test("modeChoices offers agent first — the default — then hand"):
    assertEquals(
      MainMenu.modeChoices.map(_.value),
      List(ChangeMode.Agent, ChangeMode.Hand)
    )
    assertEquals(
      MainMenu.modeChoices.map(_.label),
      List(
        "With an agent — describe the changes and let it work",
        "By hand — open in your editor"
      )
    )

  test(
    "Reconfigure/RediscoverStack labels say what they reconfigure/re-detect"
  ):
    val choices = MainMenu
      .choices(continueSessionCount = None)
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
      .choices(continueSessionCount = None)
    assert(!choices.exists(_.value == MenuItem.ResumeRun))

  test(
    "choices(resumeOffer = Some(...)) inserts ResumeRun right after RunFlow, labeled with flow and task"
  ):
    val run = InterruptedRun(
      flowName = "implement.sc",
      userPrompt = "fix the flaky integration test in the payments module"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run)
    )
    assertEquals(
      choices.map(_.value).take(2),
      List(MenuItem.RunFlow, MenuItem.ResumeRun)
    )
    assertEquals(
      choices.find(_.value == MenuItem.ResumeRun).map(_.label),
      Some(
        "Resume interrupted run — implement.sc: fix the flaky integration test in the pa…"
      )
    )

  test("choices(resumeOffer = Some(...)) label drops control characters"):
    val run = InterruptedRun(
      flowName = "fix.sc",
      userPrompt = "safe\u001b[31m text\u0007"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run)
    )
    val label = choices.find(_.value == MenuItem.ResumeRun).get.label
    assert(!label.exists(_.isControl), label)

  test(
    "choices(resumeOffer = Some(...)) label flattens a multi-line task"
  ):
    val run = InterruptedRun(
      flowName = "fix.sc",
      userPrompt = "line one\nline two"
    )
    val choices = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(run)
    )
    assertEquals(
      choices.find(_.value == MenuItem.ResumeRun).map(_.label),
      Some("Resume interrupted run — fix.sc: line one line two")
    )

  test(
    "choices(resumeOffer = Some(...)) leaves every other item's label alone"
  ):
    val withOffer = MainMenu.choices(
      continueSessionCount = None,
      resumeOffer = Some(InterruptedRun("a.sc", "short task"))
    )
    val without = MainMenu
      .choices(continueSessionCount = None)
    assertEquals(
      withOffer.filterNot(_.value == MenuItem.ResumeRun).map(_.label),
      without.map(_.label)
    )

  test("EditSettings label names both tiers it can open"):
    val choices = MainMenu
      .choices(continueSessionCount = None)
    val byValue = choices.map(c => c.value -> c.label).toMap
    assertEquals(
      byValue(MenuItem.EditSettings),
      "Edit settings — open the project or global settings file in your editor"
    )
