package orca

import munit.FunSuite

/** The invariant that keeps a stage path distinct from the flow body, and the
  * law the persisted spelling has to obey (ADR 0018 §2.1).
  */
class StagePathTest extends FunSuite:

  private def roundTrip(path: StagePath): StagePath =
    StagePath.fromValue(path.value)

  test("a stage path id cannot be minted outside StagePath"):
    // The empty id is what the type refuses; this test file is in package orca,
    // so it also pins that `private[StagePath]` is narrower than `private[orca]`.
    val errors = compileErrors("""StagePath.Stage(StagePath.Id(""))""")
    assert(
      errors.nonEmpty,
      "expected a compile error when building a stage path id outside StagePath"
    )

  test("the empty spelling reads as the flow body"):
    assertEquals(StagePath.fromValue(""), StagePath.FlowBody)

  test("the flow body round-trips through its spelling"):
    assertEquals(roundTrip(StagePath.FlowBody), StagePath.FlowBody)

  test("a stage round-trips through its spelling"):
    val stage = StagePath.FlowBody.child("Task: add multiply", 0)
    assertEquals(stage.value, "Task: add multiply#0")
    assertEquals(roundTrip(stage), stage)

  test("a nested stage round-trips through its spelling"):
    val stage = StagePath.FlowBody.child("Implement", 0).child("Task", 1)
    assertEquals(stage.value, "Implement#0/Task#1")
    assertEquals(roundTrip(stage), stage)
