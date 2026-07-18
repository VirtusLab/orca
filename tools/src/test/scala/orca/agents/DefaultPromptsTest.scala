package orca.agents

class DefaultPromptsTest extends munit.FunSuite:
  private val input = """{"task":"refactor"}"""
  private val schema = """{"type":"object"}"""
  private val config = AgentConfig()

  test("autonomous prompt embeds input and schema and forbids code fences"):
    val prompt = DefaultPrompts
      .autonomous(input, schema, config, StructuredOutputMode.RawText)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    assert(prompt.contains("no markdown code fences"))

  test("autonomous delivery rules follow the declared structured-output mode"):
    // Tool-mode backends receive their reply via a CLI-injected StructuredOutput
    // tool, so "raw JSON only" would contradict the wire; RawText backends keep
    // the raw-JSON contract verbatim.
    val raw = DefaultPrompts
      .autonomous(input, schema, config, StructuredOutputMode.RawText)
    val tool = DefaultPrompts
      .autonomous(input, schema, config, StructuredOutputMode.Tool)
    assert(raw.contains("raw JSON only"))
    assert(!raw.contains("StructuredOutput"))
    assert(tool.contains("calling the StructuredOutput tool"))
    assert(tool.contains("top-level fields as the tool's arguments"))
    assert(!tool.contains("raw JSON only"))
    assert(tool.contains(input) && tool.contains(schema))

  test("retry prompt includes the failed response, error, and raw-JSON rules"):
    val failed = """{"name":"widget"""
    val error = "expected '}' at offset 15"
    val prompt = DefaultPrompts.retry(failed, error)
    assert(prompt.contains(failed))
    assert(prompt.contains(error))
    assert(prompt.contains("no markdown code fences"))

  test(
    "interactive prompt embeds input and schema and does not ask for a marker"
  ):
    val prompt = DefaultPrompts.interactive(input, schema, config)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    // The stream-json path validates via --json-schema, not a sentinel marker.
    assert(!prompt.contains("<<<"))
    assert(!prompt.contains("marker"))
