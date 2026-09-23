package orcacaps

/** Negative compile test: a flow script, outside the `orca` package, cannot
  * construct the stage markers listeners build their stage stacks from.
  */
class StageEventsNegativeTest extends munit.FunSuite:

  test("StageStarted cannot be constructed outside the orca package"):
    val errors = compileErrors(
      """orca.events.OrcaEvent.StageStarted(orca.StagePath.FlowBody.child("x", 0))"""
    )
    assert(
      errors.contains("does not take parameters"),
      s"expected StageStarted construction to be rejected, got: $errors"
    )

  test("StageEnded cannot be constructed outside the orca package"):
    val errors = compileErrors(
      """orca.events.OrcaEvent.StageEnded(orca.StagePath.FlowBody.child("x", 0), orca.events.StageOutcome.Completed)"""
    )
    assert(
      errors.contains("does not take parameters"),
      s"expected StageEnded construction to be rejected, got: $errors"
    )
