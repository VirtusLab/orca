package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import munit.FunSuite

/** Stage path identity and its persisted form (ADR 0018 §2.1). */
class StagePathTest extends FunSuite:

  private def roundTrip(path: StagePath): StagePath =
    readFromString[StagePath](writeToString(path))

  test("a stage name spelling a nested path persists apart from that path"):
    assertNotEquals(
      writeToString[StagePath](StagePath.FlowBody.child("A#0/B", 0)),
      writeToString[StagePath](StagePath.FlowBody.child("A", 0).child("B", 0))
    )

  test("null is not a stage path"):
    intercept[JsonReaderException](readFromString[StagePath]("null")): Unit

  test("the flow body persists as an empty array"):
    assertEquals(writeToString[StagePath](StagePath.FlowBody), "[]")
    assertEquals(roundTrip(StagePath.FlowBody), StagePath.FlowBody)

  test("a nested stage round-trips through its persisted form"):
    val stage = StagePath.FlowBody.child("Implement", 0).child("Task", 1)
    assertEquals(roundTrip(stage), stage)

  test("a nested stage displays as its segments joined by slashes"):
    assertEquals(
      StagePath.FlowBody.child("Implement", 0).child("Task", 1).display,
      "Implement#0/Task#1"
    )
