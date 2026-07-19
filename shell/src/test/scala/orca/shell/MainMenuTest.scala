package orca.shell

class MainMenuTest extends munit.FunSuite:

  test("choices(None) yields the 9 ADR-order items, all enabled"):
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
    for item <- MenuItem.values if item != MenuItem.ContinueSession do
      assertEquals(byValue(item), None, s"$item should stay enabled")

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
