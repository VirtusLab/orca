package orca.shell.ui

class ChoiceTest extends munit.FunSuite:

  test("isEnabled is true when disabledReason is absent"):
    assert(Choice(1, "First").isEnabled)

  test("isEnabled is false when disabledReason is set"):
    assert(!Choice(1, "First", disabledReason = Some("no manifests")).isEnabled)

  test("renderedLabel is the plain label when enabled"):
    assertEquals(Choice(1, "First").renderedLabel, "First")

  test(
    "renderedLabel folds the reason into an unavailable suffix when disabled"
  ):
    assertEquals(
      Choice(1, "First", disabledReason = Some("no manifests")).renderedLabel,
      "First (unavailable: no manifests)"
    )

  test("disabledSelectionMessage names the choice and states its reason"):
    assertEquals(
      Choice(1, "First", disabledReason = Some("no manifests"))
        .disabledSelectionMessage,
      "'First' is unavailable: no manifests"
    )

  test("disabledSelectionMessage falls back to a generic message with no reason"):
    assertEquals(Choice(1, "First").disabledSelectionMessage, "'First' is unavailable.")
