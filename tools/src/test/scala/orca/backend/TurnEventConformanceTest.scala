package orca.backend

import orca.backend.TurnEvent.*

/** Self-test for [[TurnEventConformance.assertGrammar]] — pins the helper's own
  * verdicts so a regression in the checker (which every backend's scripted
  * tests trust) fails here rather than silently passing bad sequences.
  */
class TurnEventConformanceTest extends munit.FunSuite:

  test(
    "an AssistantMessageEnd with no preceding activity (empty message) is rejected"
  ):
    intercept[AssertionError]:
      TurnEventConformance
        .assertGrammar(List(AssistantMessageEnd), completedNormally = true)

  test("a ToolResult carrying Some(\"\") for its name is rejected"):
    intercept[AssertionError]:
      TurnEventConformance.assertGrammar(
        List(ToolResult(Some(""), ok = true, "out"), AssistantMessageEnd),
        completedNormally = true
      )

  test("trailing activity with no turn end is rejected when completedNormally"):
    intercept[AssertionError]:
      TurnEventConformance
        .assertGrammar(List(AssistantTextDelta("x")), completedNormally = true)

  test(
    "trailing activity with no turn end is allowed when not completedNormally"
  ):
    TurnEventConformance
      .assertGrammar(List(AssistantTextDelta("x")), completedNormally = false)

  test("a ToolResult opens the turn, so [ToolResult, TurnEnd] is a valid turn"):
    TurnEventConformance.assertGrammar(
      List(ToolResult(Some("bash"), ok = true, "out"), AssistantMessageEnd),
      completedNormally = true
    )
