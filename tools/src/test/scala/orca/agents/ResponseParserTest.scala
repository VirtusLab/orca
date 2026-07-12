package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

case class ParsedSample(name: String, count: Int)
    derives ConfiguredJsonValueCodec

// Mirrors the reviewer verdict shape (`{"issues":[...]}`) from the live-test
// crash log, so the regression test below reproduces the exact bytes.
case class IssuesSample(issues: List[String]) derives ConfiguredJsonValueCodec

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

  // Regression: opencode (reviewer routed through claude-haiku) echoed its own
  // structured-output tool's argument envelope — the tool's sole parameter is
  // named `input` — back as the agent response instead of the bare schema
  // value. Live-test finding B: `{"input":"{\"issues\":[]}"}` aborted the
  // whole flow with a MalformedAgentOutputException.
  test("unwraps a lone input-key envelope whose value is a JSON string"):
    // The exact payload observed in the opencode live-test crash log.
    val observed = """{"input":"{\"issues\":[]}"}"""
    assertEquals(
      ResponseParser.parse[IssuesSample](observed),
      IssuesSample(Nil)
    )

  test("unwraps a lone input-key envelope, string-encoded value"):
    val wrapped =
      """{"input":"{\"name\":\"widget\",\"count\":3}"}"""
    assertEquals(ResponseParser.parse[ParsedSample](wrapped), expected)

  test("unwraps a lone input-key envelope, nested-object value"):
    val wrapped = """{"input":{"name":"widget","count":3}}"""
    assertEquals(ResponseParser.parse[ParsedSample](wrapped), expected)

  test("does not unwrap when the envelope has a second key"):
    val wrapped =
      """{"input":"{\"name\":\"widget\",\"count\":3}","extra":1}"""
    intercept[MalformedAgentOutputException]:
      ResponseParser.parse[ParsedSample](wrapped)

  test("does not unwrap when the input value isn't valid schema JSON"):
    val wrapped = """{"input":"not json at all"}"""
    intercept[MalformedAgentOutputException]:
      ResponseParser.parse[ParsedSample](wrapped)

  test("does not unwrap a lone key other than \"input\""):
    val wrapped = """{"payload":"{\"name\":\"widget\",\"count\":3}"}"""
    intercept[MalformedAgentOutputException]:
      ResponseParser.parse[ParsedSample](wrapped)
