package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import io.circe.parser.parse
import munit.FunSuite
import orca.util.JsonSchemaGen

private enum Colour derives JsonData:
  case Red, Green

private case class Palette(
    main: Colour,
    all: List[Colour],
    accent: Option[Colour]
) derives JsonData

class JsonDataEnumTest extends FunSuite:

  test("a parameterless enum's schema is a string enum of its case names"):
    val schema = parse(JsonSchemaGen[Colour]).toOption.get.hcursor
    assertEquals(schema.get[String]("type"), Right("string"))
    assertEquals(
      schema.get[Set[String]]("enum"),
      Right(Set("Red", "Green"))
    )

  test("a name that is not one of the enum's cases fails decoding"):
    val _ = intercept[JsonReaderException](readFromString[Colour]("\"Blue\""))

  private def assertMatchesSchema(json: String): Unit =
    val errors = JsonSchemaFactory
      .getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(JsonSchemaGen[Palette])
      .validate(json, InputFormat.JSON)
    assert(errors.isEmpty, s"$json does not match the schema: $errors")

  test("enum fields are written as the schema describes them"):
    val palette = Palette(Colour.Red, List(Colour.Green), Some(Colour.Red))
    val json = writeToString(palette)
    assertMatchesSchema(json)
    assertEquals(readFromString[Palette](json), palette)

  test("an optional enum sent as null matches the schema and decodes"):
    // Strict mode makes every field required, so an agent sends `null`.
    val json = """{"main":"Green","all":[],"accent":null}"""
    assertMatchesSchema(json)
    assertEquals(
      readFromString[Palette](json),
      Palette(Colour.Green, Nil, None)
    )
