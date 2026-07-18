package orca

/** Tests for the InStage capability type (ADR 0018 §2.2). */
class InStageTest extends munit.FunSuite:

  test(
    "runtime code (private[orca]) can mint an InStage and pass it to a block"
  ):
    val token: InStage = InStage.unsafe
    def needsStage(using InStage): Boolean = true
    assert(needsStage(using token))

end InStageTest
