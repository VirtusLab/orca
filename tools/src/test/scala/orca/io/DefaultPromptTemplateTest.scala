package orca.io

import orca.LlmConfig

class DefaultPromptTemplateTest extends munit.FunSuite:
  private val input = """{"task":"refactor"}"""
  private val schema = """{"type":"object"}"""
  private val config = LlmConfig.default

  test("autonomous prompt embeds input and schema and forbids code fences"):
    val prompt = DefaultPromptTemplate.autonomous(input, schema, config)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    assert(prompt.contains("no markdown code fences"))
    assert(!prompt.contains(DefaultPromptTemplate.DoneMarker))

  test("retry prompt includes the failed response, error, and raw-JSON rules"):
    val failed = """{"name":"widget"""
    val error = "expected '}' at offset 15"
    val prompt = DefaultPromptTemplate.retry(failed, error)
    assert(prompt.contains(failed))
    assert(prompt.contains(error))
    assert(prompt.contains("no markdown code fences"))

  test(
    "interactive prompt embeds input, schema, and ORCA_DONE on its own line"
  ):
    val prompt = DefaultPromptTemplate.interactive(input, schema, config)
    assert(prompt.contains(input))
    assert(prompt.contains(schema))
    val lineWithMarker =
      prompt.linesIterator.find(_.contains(DefaultPromptTemplate.DoneMarker))
    assert(lineWithMarker.isDefined, "marker missing from interactive prompt")
    assert(
      lineWithMarker.exists(_.contains("on its own line")),
      "interactive prompt should instruct the marker be emitted on its own line"
    )
