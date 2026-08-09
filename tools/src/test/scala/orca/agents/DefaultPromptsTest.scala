package orca.agents

class DefaultPromptsTest extends munit.FunSuite:
  private val input = """{"task":"refactor"}"""
  private val schema = """{"type":"object"}"""
  private val config = AgentConfig()
  private val failed = """{"name":"widget"""
  private val parseError = "expected '}' at offset 15"

  test("autonomous prompt for a RawText backend ships the schema and rules"):
    // Nothing on the wire holds a RawText backend to the schema on every turn,
    // so the prompt carries the whole contract.
    val prompt = DefaultPrompts
      .autonomous(input, schema, config, StructuredOutputMode.RawText)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    assert(prompt.contains("no markdown code fences"))

  test(
    "autonomous prompt for a Tool backend replaces the schema and JSON rules " +
      "with the tool instruction"
  ):
    // The CLI-injected StructuredOutput tool already carries the schema and
    // makes the call mandatory, so repeating either would be dead weight — and
    // "raw JSON only" would contradict the wire.
    val prompt = DefaultPrompts
      .autonomous(input, schema, config, StructuredOutputMode.Tool)
    assert(prompt.contains(input))
    assert(prompt.contains("calling the StructuredOutput tool"))
    assert(!prompt.contains(schema))
    assert(!prompt.contains("raw JSON only"))

  test("retry prompt for a RawText backend repeats the raw-JSON rules"):
    val prompt = DefaultPrompts
      .retry(failed, parseError, StructuredOutputMode.RawText)
    assert(prompt.contains(failed))
    assert(prompt.contains(parseError))
    assert(prompt.contains("no markdown code fences"))

  test("retry prompt for a Tool backend asks for the tool call instead"):
    // A corrective turn that asked for raw JSON would talk the model out of the
    // tool call the drain and the result extraction both expect.
    val prompt = DefaultPrompts
      .retry(failed, parseError, StructuredOutputMode.Tool)
    assert(prompt.contains(failed))
    assert(prompt.contains(parseError))
    assert(prompt.contains("calling the StructuredOutput tool"))
    assert(!prompt.contains("raw JSON only"))

  test(
    "interactive prompt embeds input and schema and does not ask for a marker"
  ):
    val prompt = DefaultPrompts.interactive(input, schema, config)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    // The stream-json path validates via --json-schema, not a sentinel marker.
    assert(!prompt.contains("<<<"))
    assert(!prompt.contains("marker"))
