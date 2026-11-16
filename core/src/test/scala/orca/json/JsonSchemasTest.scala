package orca.json

import io.circe.parser.parse
import io.circe.{Json, JsonObject}

class JsonSchemasTest extends munit.FunSuite:

  private def strictOrRefusal(input: String): Either[String, Json] =
    JsonSchemas.toOpenAiStrict(parse(input).toOption.get)

  private def strict(input: String): Json = strictOrRefusal(input).toOption.get

  test("object node gains additionalProperties:false and full required list"):
    // OpenAI strict mode demands every object declare additionalProperties
    // false AND list every property in required (nullability is how
    // optionality is expressed). Tapir's output doesn't supply either.
    val out = strict(
      """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}}}"""
    )
    val obj = out.asObject.get
    assertEquals(obj("additionalProperties"), Some(Json.False))
    val required =
      obj("required").get.asArray.get.map(_.asString.get).toSet
    assertEquals(required, Set("a", "b"))

  test("object without properties is left alone"):
    // A marker `{"type":"object"}` (no properties) carries no constraints
    // to enforce — don't graft additionalProperties onto an empty shell.
    val out = strict("""{"type":"object"}""")
    assertEquals(out.asObject.get("additionalProperties"), None)
    assertEquals(out.asObject.get("required"), None)

  test("nested object schemas inside $defs are transformed too"):
    // `$defs` is where Tapir parks the case class definitions referenced by
    // `$ref` — they're the meaningful object schemas and must be strict too.
    val out = strict(
      """{
        |  "type":"object",
        |  "properties":{"t":{"$ref":"#/$defs/Task"}},
        |  "$defs":{
        |    "Task":{"type":"object","properties":{"title":{"type":"string"}}}
        |  }
        |}""".stripMargin
    )
    val task =
      out.hcursor
        .downField("$defs")
        .downField("Task")
        .as[JsonObject]
        .toOption
        .get
    assertEquals(task("additionalProperties"), Some(Json.False))
    assertEquals(
      task("required").get.asArray.get.map(_.asString.get).toList,
      List("title")
    )

  test(
    "a Map-shaped additionalProperties field (Tapir's real Map shape) fails fast with an actionable message"
  ):
    // The realistic shape Tapir emits for a Map[String,T] field: an object
    // node with additionalProperties but no `properties` key at all.
    // The Map-shape check runs before the `properties`
    // check, so this fails fast here rather than letting the non-strict
    // schema reach codex/claude and bounce back as an opaque
    // `invalid_json_schema`.
    val refusal = strictOrRefusal(
      """{"type":"object","additionalProperties":{"type":"integer"}}"""
    )
    assert(
      refusal.left.exists(_.contains("List of key/value case classes")),
      s"expected actionable Map-field message, got: $refusal"
    )
