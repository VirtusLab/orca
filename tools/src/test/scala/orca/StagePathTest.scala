package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import io.circe.parser.parse
import munit.FunSuite
import orca.json.{JsonData, SchemaParitySuite}

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

  test("a stage's schema is an array of name-occurrence segments"):
    val schema = summon[JsonData[StagePath.Stage]].schema
    assertEquals(
      SchemaParitySuite.render(schema),
      parse("""{
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$defs": {
          "Segment": {
            "title": "Segment",
            "type": "object",
            "required": ["name", "occurrence"],
            "properties": {
              "name": {"type": "string"},
              "occurrence": {"type": "integer", "format": "int32"}
            }
          }
        },
        "type": "array",
        "items": {"$ref": "#/$defs/Segment"}
      }""").toTry.get
    )
