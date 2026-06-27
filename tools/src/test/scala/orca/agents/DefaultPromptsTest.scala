package orca.agents

class DefaultPromptsTest extends munit.FunSuite:
  private val input = """{"task":"refactor"}"""
  private val schema = """{"type":"object"}"""
  private val config = AgentConfig.default

  test("autonomous prompt embeds input and schema and forbids code fences"):
    val prompt = DefaultPrompts.autonomous(input, schema, config)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    assert(prompt.contains("no markdown code fences"))

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
    // Explicit negative checks: the stream-json path uses --json-schema for
    // validation, not a sentinel + transcript scrape.
    assert(!prompt.contains("<<<"))
    assert(!prompt.contains("marker"))
