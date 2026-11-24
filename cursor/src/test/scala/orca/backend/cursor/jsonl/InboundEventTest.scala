package orca.backend.cursor.jsonl

class InboundEventTest extends munit.FunSuite:

  test("parses system init event"):
    val line =
      """{"type":"system","subtype":"init","session_id":"abc","model":"Composer 2.5"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Init(sid, model) =>
        assertEquals(sid, "abc")
        assertEquals(model, Some("Composer 2.5"))
      case other => fail(s"expected Init, got $other")

  test("parses assistant nested text"):
    val line =
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"OK"}]}}"""
    InboundEvent.parse(line) match
      case InboundEvent.AssistantText(text) => assertEquals(text, "OK")
      case other => fail(s"expected AssistantText, got $other")

  test("parses success result with usage"):
    val line =
      """{"type":"result","subtype":"success","is_error":false,"result":"OK","usage":{"inputTokens":10,"outputTokens":2,"cacheReadTokens":1,"cacheWriteTokens":0}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(text, usage, status) =>
        assertEquals(text, Some("OK"))
        assertEquals(usage.get.inputTokens, 11L)
        assertEquals(usage.get.outputTokens, 2L)
        assert(status.isSuccess)
      case other => fail(s"expected Result, got $other")
  test("fresh input and cache buckets are disjoint with unknown cost"):
    val line =
      """{"type":"result","subtype":"success","usage":{"inputTokens":709,"cacheReadTokens":16448,"cacheWriteTokens":0,"outputTokens":53}}"""
    val InboundEvent.Result(_, Some(usage), _) =
      InboundEvent.parse(line): @unchecked
    assertEquals(usage.freshInputTokens, 709L)
    assertEquals(usage.cacheReadInputTokens, 16448L)
    assertEquals(usage.cacheWriteInputTokens, 0L)
    assertEquals(usage.inputTokens, 17157L)
    assertEquals(usage.outputTokens, 53L)
    assertEquals(usage.cost, None)

  test("cache-write tokens are preserved and contribute to total input"):
    val line =
      """{"type":"result","subtype":"success","usage":{"inputTokens":10,"cacheReadTokens":20,"cacheWriteTokens":30,"outputTokens":2}}"""
    val InboundEvent.Result(_, Some(usage), _) =
      InboundEvent.parse(line): @unchecked
    assertEquals(usage.cacheWriteInputTokens, 30L)
    assertEquals(usage.inputTokens, 60L)

  test("absent usage remains absent"):
    val InboundEvent.Result(_, usage, _) =
      InboundEvent.parse("""{"type":"result","subtype":"error"}"""): @unchecked
    assertEquals(usage, None)

  test("unknown event and subtype are ignored explicitly"):
    assertEquals(
      InboundEvent.parse("""{"type":"future","subtype":"v2"}"""),
      InboundEvent.Unknown("future")
    )
    assertEquals(
      InboundEvent.parse("""{"type":"system","subtype":"future"}"""),
      InboundEvent.Unknown("system/future")
    )

  test("unknown assistant content does not hide known text"):
    assertEquals(
      InboundEvent.parse(
        """{"type":"assistant","message":{"content":[{"type":"future","text":"ignore"},{"type":"text","text":"answer"}]}}"""
      ),
      InboundEvent.AssistantText("answer")
    )

  test("completed tool with only is_error is failed"):
    val line =
      """{"type":"tool_call","subtype":"completed","call_id":"read-1","tool_call":{"readToolCall":{"result":{"is_error":true}}}}"""
    assertEquals(
      InboundEvent.parse(line),
      InboundEvent.ToolCompleted(
        name = Some("readToolCall"),
        ok = false,
        payload = line
      )
    )

  test("unknown tool subtype ignores a payload with a different schema"):
    assertEquals(
      InboundEvent.parse(
        """{"type":"tool_call","subtype":"future","tool_call":42}"""
      ),
      InboundEvent.Unknown("tool_call/future")
    )
