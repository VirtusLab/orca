package orca.backend

import orca.backend.ConversationEvent.*

/** Self-test for [[ConversationEventConformance.assertGrammar]] — pins the
  * helper's own verdicts so a regression in the checker (which every backend's
  * scripted tests trust) fails here rather than silently passing bad sequences.
  * munit's `assert`/`fail` both throw `FailException <: AssertionError`, so an
  * expected rejection is an intercepted `AssertionError`.
  */
class ConversationEventConformanceTest extends munit.FunSuite:

  test(
    "an AssistantTurnEnd with no preceding activity (empty turn) is rejected"
  ):
    intercept[AssertionError]:
      ConversationEventConformance
        .assertGrammar(List(AssistantTurnEnd), completedNormally = true)

  test("a ToolResult carrying Some(\"\") for its name is rejected"):
    intercept[AssertionError]:
      ConversationEventConformance.assertGrammar(
        List(ToolResult(Some(""), ok = true, "out"), AssistantTurnEnd),
        completedNormally = true
      )

  test("trailing activity with no turn end is rejected when completedNormally"):
    intercept[AssertionError]:
      ConversationEventConformance
        .assertGrammar(List(AssistantTextDelta("x")), completedNormally = true)

  test(
    "trailing activity with no turn end is allowed when not completedNormally"
  ):
    ConversationEventConformance
      .assertGrammar(List(AssistantTextDelta("x")), completedNormally = false)

  test("a ToolResult opens the turn, so [ToolResult, TurnEnd] is a valid turn"):
    ConversationEventConformance.assertGrammar(
      List(ToolResult(Some("bash"), ok = true, "out"), AssistantTurnEnd),
      completedNormally = true
    )
