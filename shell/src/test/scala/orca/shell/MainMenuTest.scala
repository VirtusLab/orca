package orca.shell

import orca.shell.resume.InterruptedRun

class MainMenuTest extends munit.FunSuite:

  test("choices(None) yields the 10 ADR-order items, all enabled"):
    val values = MainMenu
      .choices(continueDisabledReason = None, newestRunSessionCount = 2)
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
        .choices(continueDisabledReason = None, newestRunSessionCount = 2)
        .forall(_.disabledReason.isEmpty)
    )

  test("choices(Some(reason)) disables only ContinueSession, with that reason"):
    val reason = "no sessions recorded yet"
    val choices = MainMenu
      .choices(continueDisabledReason = Some(reason), newestRunSessionCount = 0)
    val byValue = choices.map(c => c.value -> c.disabledReason).toMap
    assertEquals(byValue(MenuItem.ContinueSession), Some(reason))
    // ResumeRun is ABSENT (not present-but-enabled) with no resumeOffer given
    // — excluded here rather than asserted, since it isn't in `byValue` at all.
    for
      item <- MenuItem.values
      if item != MenuItem.ContinueSession && item != MenuItem.ResumeRun
    do assertEquals(byValue(item), None, s"$item should stay enabled")

  test(
    "choices(None) labels ContinueSession with the newest run's session count"
  ):
    val choices = MainMenu
      .choices(continueDisabledReason = None, newestRunSessionCount = 3)
    val label =
      choices.find(_.value == MenuItem.ContinueSession).get.label
    assertEquals(
      label,
      "Continue a session from the last flow run (3 session(s))"
    )

  test("choices(Some(reason)) keeps the plain ContinueSession label"):
    val choices = MainMenu.choices(
      continueDisabledReason = Some("no sessions recorded yet"),
      newestRunSessionCount = 5
    )
    val label =
      choices.find(_.value == MenuItem.ContinueSession).get.label
    assertEquals(label, "Continue a session")

  test(
    "EditFlow/CreateFlow/ForkFlow labels name both hand and agent modes"
  ):
    val choices = MainMenu
      .choices(continueDisabledReason = None, newestRunSessionCount = 0)
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
      .choices(continueDisabledReason = None, newestRunSessionCount = 0)
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
      .choices(continueDisabledReason = None, newestRunSessionCount = 0)
    assert(!choices.exists(_.value == MenuItem.ResumeRun))

  test(
    "choices(resumeOffer = Some(...)) inserts ResumeRun right after RunFlow, labeled with flow and task"
  ):
    val run = InterruptedRun(
      flowName = "implement.sc",
      userPrompt = "fix the flaky integration test in the payments module"
    )
    val choices = MainMenu.choices(
      continueDisabledReason = None,
      newestRunSessionCount = 0,
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
      continueDisabledReason = None,
      newestRunSessionCount = 0,
      resumeOffer = Some(run)
    )
    val label = choices.find(_.value == MenuItem.ResumeRun).get.label
    assert(!label.exists(_.isControl), label)

  test(
    "choices(resumeOffer = Some(...)) label flattens a multi-line task and always ends in an ellipsis"
  ):
    val run = InterruptedRun(
      flowName = "fix.sc",
      userPrompt = "line one\nline two"
    )
    val choices = MainMenu.choices(
      continueDisabledReason = None,
      newestRunSessionCount = 0,
      resumeOffer = Some(run)
    )
    assertEquals(
      choices.find(_.value == MenuItem.ResumeRun).map(_.label),
      Some("Resume interrupted run — fix.sc: line one line two…")
    )

  test(
    "choices(resumeOffer = Some(...)) leaves every other item's label alone"
  ):
    val withOffer = MainMenu.choices(
      continueDisabledReason = None,
      newestRunSessionCount = 0,
      resumeOffer = Some(InterruptedRun("a.sc", "short task"))
    )
    val without = MainMenu
      .choices(continueDisabledReason = None, newestRunSessionCount = 0)
    assertEquals(
      withOffer.filterNot(_.value == MenuItem.ResumeRun).map(_.label),
      without.map(_.label)
    )

  test("EditSettings label names both tiers it can open"):
    val choices = MainMenu
      .choices(continueDisabledReason = None, newestRunSessionCount = 0)
    val byValue = choices.map(c => c.value -> c.label).toMap
    assertEquals(
      byValue(MenuItem.EditSettings),
      "Edit settings — open the project or global settings file in your editor"
    )
