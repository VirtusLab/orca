package orca.review

import orca.Title
import orca.llm.given
import orca.io.JsonSchemaGen

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}

class JsonSchemaGenTest extends munit.FunSuite:
  private def compiledSchema =
    val schemaString = JsonSchemaGen[ReviewResult]
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    factory.getSchema(schemaString)

  test("generated schema validates a well-formed ReviewResult"):
    val sample = ReviewResult(
      issues = List(
        ReviewIssue(
          Severity.Info,
          0.8,
          Title("Hello"),
          "hello",
          None,
          None,
          None
        )
      )
    )
    val errors =
      compiledSchema.validate(writeToString(sample), InputFormat.JSON)
    assert(errors.isEmpty, s"Validation errors: $errors")

  test("generated schema rejects an unknown severity value"):
    val invalid =
      """{"issues":[{"severity":"Bogus","confidence":0.5,"description":"x"}]}"""
    val errors = compiledSchema.validate(invalid, InputFormat.JSON)
    assert(!errors.isEmpty, "Schema should reject unknown severity values")
