package orca.util

import io.circe.parser.parse
import io.circe.{Json, JsonObject}

class JsonSchemaGenTest extends munit.FunSuite:

  private def strict(input: String): Json =
    JsonSchemaGen.toOpenAiStrict(parse(input).toOption.get)

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
    // `rejectMapShapedAdditionalProperties` runs before the `properties`
    // check, so this fails fast here rather than letting the non-strict
    // schema reach codex/claude and bounce back as an opaque
    // `invalid_json_schema`.
    val ex = intercept[orca.OrcaFlowException]:
      strict(
        """{"type":"object","additionalProperties":{"type":"integer"}}"""
      )
    assert(
      ex.getMessage.contains("List of key/value case classes"),
      s"expected actionable Map-field message, got: ${ex.getMessage}"
    )

  test("apply emits no $schema dialect declaration"):
    // TapirSchemaToJsonSchema stamps `$schema: .../draft/2020-12/schema` on its
    // output, but the claude CLI's `--json-schema` validator has no 2020-12
    // meta-schema registered and rejects any schema declaring that dialect. No
    // consumer needs the declaration, so `apply` strips it.
    import sttp.tapir.Schema
    import sttp.tapir.generic.auto.given
    case class Sample(name: String)
    val parsed = parse(JsonSchemaGen[Sample]).toOption.get
    assertEquals(parsed.asObject.get("$schema"), None)

  test("apply preserves nullability on an Option field through the transform"):
    // End-to-end check that the strict transform doesn't collapse Tapir's
    // nullable encoding — the agent must be able to emit `null` for an
    // Option-typed field that's now `required`.
    import sttp.tapir.Schema
    import sttp.tapir.generic.auto.given
    case class Sample(name: String, note: Option[String])
    val parsed = parse(JsonSchemaGen[Sample]).toOption.get
    val noteType = parsed.hcursor
      .downField("properties")
      .downField("note")
      .downField("type")
      .as[List[String]]
      .toOption
      .get
      .toSet
    assert(
      noteType.contains("null"),
      s"note type should be nullable: $noteType"
    )
