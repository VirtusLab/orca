package orca.agents

import io.circe.parser.parse
import orca.json.SchemaDerivation
import sttp.tapir.Schema

class JsonSchemaGenTest extends munit.FunSuite:

  test("apply emits no $schema dialect declaration"):
    // TapirSchemaToJsonSchema stamps `$schema: .../draft/2020-12/schema` on its
    // output, but the claude CLI's `--json-schema` validator has no 2020-12
    // meta-schema registered and rejects any schema declaring that dialect. No
    // consumer needs the declaration, so `apply` strips it.
    case class Sample(name: String)
    given Schema[Sample] = SchemaDerivation.derived
    val parsed = parse(JsonSchemaGen[Sample]).toOption.get
    assertEquals(parsed.asObject.get("$schema"), None)

  test("apply preserves nullability on an Option field through the transform"):
    // End-to-end check that the strict transform doesn't collapse Tapir's
    // nullable encoding — the agent must be able to emit `null` for an
    // Option-typed field that's now `required`.
    case class Sample(name: String, note: Option[String])
    given Schema[Sample] = SchemaDerivation.derived
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
