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
      case other                            => fail(s"expected AssistantText, got $other")

  test("parses success result with usage"):
    val line =
      """{"type":"result","subtype":"success","is_error":false,"result":"OK","usage":{"inputTokens":10,"outputTokens":2,"cacheReadTokens":1,"cacheWriteTokens":0}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(text, usage, status) =>
        assertEquals(text, Some("OK"))
        assertEquals(usage.inputTokens, 10L)
        assertEquals(usage.outputTokens, 2L)
        assert(status.isSuccess)
      case other => fail(s"expected Result, got $other")