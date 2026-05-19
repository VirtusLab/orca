package orca.llm

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

  test("extracts JSON object when the agent prepends prose"):
    val raw =
      "The multiply method already exists.\n\n{\"name\":\"widget\",\"count\":3}"
    assertEquals(ResponseParser.parse[ParsedSample](raw), expected)

  test("extracts JSON object when the agent appends trailing prose"):
    val raw = """{"name":"widget","count":3} — all set."""
    assertEquals(ResponseParser.parse[ParsedSample](raw), expected)

  test("raises MalformedAgentOutputException with raw payload on failure"):
    val e = intercept[MalformedAgentOutputException]:
      ResponseParser.parse[ParsedSample]("not json at all")
    assertEquals(e.rawOutput, "not json at all")
    assert(e.shortCause.nonEmpty)
    // No hex dump nor multiline buffer in the user-facing message.
    assert(!e.getMessage.contains("+---"))
    assert(!e.getMessage.contains("\n"))
