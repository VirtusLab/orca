package orca.claude

import orca.OrcaEvent

class ClaudeNdjsonParserTest extends munit.FunSuite:

  test("emits LlmOutput for each text block in assistant messages"):
    val ndjson =
      """{"type":"system","subtype":"init","session_id":"s"}
        |{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}
        |{"type":"assistant","message":{"content":[{"type":"text","text":"world"}]}}
        |{"type":"result","result":"done","session_id":"s"}""".stripMargin

    val events = ClaudeNdjsonParser.parse(ndjson)

    assertEquals(
      events,
      List(
        OrcaEvent.LlmOutput("hello"),
        OrcaEvent.LlmOutput("world")
      )
    )

  test("emits one LlmOutput per text block when a message has multiple blocks"):
    val ndjson =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}"""
    assertEquals(
      ClaudeNdjsonParser.parse(ndjson),
      List(OrcaEvent.LlmOutput("a"), OrcaEvent.LlmOutput("b"))
    )

  test("ignores non-text content blocks such as tool_use"):
    val ndjson =
      """{"type":"assistant","message":{"content":[{"type":"tool_use","text":null},{"type":"text","text":"ok"}]}}"""
    assertEquals(
      ClaudeNdjsonParser.parse(ndjson),
      List(OrcaEvent.LlmOutput("ok"))
    )

  test("ignores non-assistant events"):
    val ndjson =
      """{"type":"system","subtype":"init","session_id":"s"}
        |{"type":"result","result":"done","session_id":"s"}""".stripMargin
    assertEquals(ClaudeNdjsonParser.parse(ndjson), Nil)

  test("skips text blocks that are missing the text field"):
    val ndjson =
      """{"type":"assistant","message":{"content":[{"type":"text"},{"type":"text","text":"kept"}]}}"""
    assertEquals(
      ClaudeNdjsonParser.parse(ndjson),
      List(OrcaEvent.LlmOutput("kept"))
    )
