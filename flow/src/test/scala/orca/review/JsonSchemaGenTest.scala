package orca.review

import orca.llm.given
import orca.util.JsonSchemaGen

import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}

class JsonSchemaGenTest extends munit.FunSuite:
  private def compiledSchema =
    val schemaString = JsonSchemaGen[ReviewResult]
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    factory.getSchema(schemaString)

  test("generated schema validates a well-formed ReviewResult"):
    // The agent emits OpenAI-strict JSON: every nullable field is present
    // with a real null rather than omitted. The schema must accept that.
    val sample =
      """{"issues":[{
        |  "severity":"Info",
        |  "confidence":0.8,
        |  "title":"Hello",
        |  "description":"hello",
        |  "file":null,
        |  "line":null,
        |  "suggestion":null
        |}]}""".stripMargin
    val errors = compiledSchema.validate(sample, InputFormat.JSON)
    assert(errors.isEmpty, s"Validation errors: $errors")

  test("generated schema rejects an unknown severity value"):
    val invalid =
      """{"issues":[{
        |  "severity":"Bogus",
        |  "confidence":0.5,
        |  "title":"x",
        |  "description":"x",
        |  "file":null,
        |  "line":null,
        |  "suggestion":null
        |}]}""".stripMargin
    val errors = compiledSchema.validate(invalid, InputFormat.JSON)
    assert(!errors.isEmpty, "Schema should reject unknown severity values")

  test("generated schema rejects a payload that omits a nullable field"):
    // Strict mode treats every property as required (nullability is the
    // mechanism for optionality). Omitting `suggestion` should be rejected.
    val invalid =
      """{"issues":[{
        |  "severity":"Info",
        |  "confidence":0.5,
        |  "title":"x",
        |  "description":"x",
        |  "file":null,
        |  "line":null
        |}]}""".stripMargin
    val errors = compiledSchema.validate(invalid, InputFormat.JSON)
    assert(
      !errors.isEmpty,
      "Schema should reject payloads that omit a nullable property"
    )

  test("generated schema rejects additional properties"):
    val invalid =
      """{"issues":[],"unexpected":"x"}"""
    val errors = compiledSchema.validate(invalid, InputFormat.JSON)
    assert(
      !errors.isEmpty,
      "Schema should reject unknown top-level keys (additionalProperties:false)"
    )
