package orca.shell

class MainMenuTest extends munit.FunSuite:

  test("choices(None) yields the 7 ADR-order items, all enabled"):
    val values = MainMenu.choices(continueDisabledReason = None).map(_.value)
    assertEquals(
      values,
      List(
        MenuItem.RunFlow,
        MenuItem.ViewFlow,
        MenuItem.EditFlow,
        MenuItem.CreateFlow,
        MenuItem.ContinueSession,
        MenuItem.Reconfigure,
        MenuItem.Exit
      )
    )
    assert(
      MainMenu
        .choices(continueDisabledReason = None)
        .forall(_.disabledReason.isEmpty)
    )

  test("choices(Some(reason)) disables only ContinueSession, with that reason"):
    val reason = "no sessions recorded yet"
    val choices = MainMenu.choices(continueDisabledReason = Some(reason))
    val byValue = choices.map(c => c.value -> c.disabledReason).toMap
    assertEquals(byValue(MenuItem.ContinueSession), Some(reason))
    for item <- MenuItem.values if item != MenuItem.ContinueSession do
      assertEquals(byValue(item), None, s"$item should stay enabled")
