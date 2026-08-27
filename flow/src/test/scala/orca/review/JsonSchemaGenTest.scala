package orca.review

import orca.agents.given
import orca.util.JsonSchemaGen

import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}

class JsonSchemaGenTest extends munit.FunSuite:
  private def compiledResultSchema =
    val schemaString = JsonSchemaGen[ReviewResult]
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    factory.getSchema(schemaString)

  test("generated schema validates a well-formed ReviewResult"):
    // The agent emits OpenAI-strict JSON: every nullable field is present
    // with a real null rather than omitted. The schema must accept that.
    val sample =
      """{"issues":[{
        |  "title":"Hello",
        |  "description":"hello",
        |  "location":null,
        |  "suggestion":null
        |}]}""".stripMargin
    val errors = compiledResultSchema.validate(sample, InputFormat.JSON)
    assert(errors.isEmpty, s"Validation errors: $errors")

  test("generated schema validates an issue that names a location"):
    // The only sample reaching the nested `Location` object's own schema path:
    // everywhere else `location` is null, which never descends into it.
    val sample =
      """{"issues":[{
        |  "title":"Hello",
        |  "description":"hello",
        |  "location":{"file":"orca/review/Lint.scala","line":42},
        |  "suggestion":null
        |}]}""".stripMargin
    val errors = compiledResultSchema.validate(sample, InputFormat.JSON)
    assert(errors.isEmpty, s"Validation errors: $errors")

  test("generated schema rejects a payload that omits a nullable field"):
    // Strict mode treats every property as required (nullability is the
    // mechanism for optionality). Omitting `suggestion` should be rejected.
    val invalid =
      """{"issues":[{
        |  "title":"x",
        |  "description":"x",
        |  "location":null
        |}]}""".stripMargin
    val errors = compiledResultSchema.validate(invalid, InputFormat.JSON)
    assert(
      !errors.isEmpty,
      "Schema should reject payloads that omit a nullable property"
    )

  test("generated schema rejects additional properties"):
    val invalid =
      """{"issues":[],"unexpected":"x"}"""
    val errors = compiledResultSchema.validate(invalid, InputFormat.JSON)
    assert(
      !errors.isEmpty,
      "Schema should reject unknown top-level keys (additionalProperties:false)"
    )

  private def compiledSelectionSchema =
    JsonSchemaFactory
      .getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(JsonSchemaGen[SelectedReviewers])

  test("generated schema rejects a selection that names no reviewer"):
    // What claude:haiku and codex reply on a small change when the schema
    // permits it; the selector's fallback then runs the whole roster.
    val errors =
      compiledSelectionSchema.validate("""{"names":[]}""", InputFormat.JSON)
    assert(!errors.isEmpty, "Schema should reject an empty selection")

  test("generated schema accepts a selection that names one reviewer"):
    val errors = compiledSelectionSchema
      .validate("""{"names":["code-functionality"]}""", InputFormat.JSON)
    assert(errors.isEmpty, s"Validation errors: $errors")
