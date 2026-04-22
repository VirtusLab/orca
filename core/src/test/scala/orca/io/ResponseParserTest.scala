package orca.io

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

case class ParsedSample(name: String, count: Int)
    derives ConfiguredJsonValueCodec

class ResponseParserTest extends munit.FunSuite:
  private val expected = ParsedSample("widget", 3)

  test("parses raw JSON"):
    val parsed =
      ResponseParser.parse[ParsedSample]("""{"name":"widget","count":3}""")
    assertEquals(parsed, expected)

  test("strips markdown fences with language tag before parsing"):
    val fenced = "```json\n{\"name\":\"widget\",\"count\":3}\n```"
    assertEquals(ResponseParser.parse[ParsedSample](fenced), expected)

  test("strips bare markdown fences before parsing"):
    val fenced = "```\n{\"name\":\"widget\",\"count\":3}\n```"
    assertEquals(ResponseParser.parse[ParsedSample](fenced), expected)

  test("strips single-line markdown fences before parsing"):
    val fenced = """```{"name":"widget","count":3}```"""
    assertEquals(ResponseParser.parse[ParsedSample](fenced), expected)

  test("throws on malformed JSON"):
    intercept[JsonReaderException]:
      ResponseParser.parse[ParsedSample]("not json at all")
