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

  for (completed, index) <- CursorToolFixtures.completed.zipWithIndex do
    test(s"captured completion selects editToolCall in order ${index + 1}"):
      assertEquals(
        InboundEvent.parse(completed),
        InboundEvent.ToolCompleted(Some("editToolCall"), true, completed)
      )

    test(s"captured start selects editToolCall in order ${index + 1}"):
      val started = completed.replace("\"completed\"", "\"started\"")
      assertEquals(
        InboundEvent.parse(started),
        InboundEvent.ToolStarted("editToolCall", started)
      )

  test("metadata-bearing completion preserves a defined false error"):
    val line =
      """{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{"hookAdditionalContexts":[],"toolCallId":"c","startedAtMs":"1","completedAtMs":"2","editToolCall":{"result":{"error":false}}}}"""
    assertEquals(
      InboundEvent.parse(line),
      InboundEvent.ToolCompleted(Some("editToolCall"), false, line)
    )

  for
    subtype <- List("started", "completed")
    members <- List(
      """"readToolCall":42,"editToolCall":[]""",
      """"editToolCall":[],"readToolCall":42""",
      """"editToolCall":{"result":{}},"readToolCall":{"result":{}}""",
      """"shell":{"result":{}},"read":{"result":{}}""",
      """"read":{"result":{}},"shell":{"result":{}}"""
    )
  do
    test(s"$subtype rejects ambiguous tool members: $members"):
      val line =
        s"""{"type":"tool_call","subtype":"$subtype","call_id":"c","tool_call":{$members}}"""
      val error = intercept[IllegalArgumentException](InboundEvent.parse(line))
      assert(error.getMessage.contains("Ambiguous Cursor tool_call members"))

  for metadata <- List(
      "\"hookAdditionalContexts\":{}",
      "\"toolCallId\":\"c\"",
      "\"startedAtMs\":\"1\"",
      "\"completedAtMs\":\"2\"",
      """"hookAdditionalContexts":[],"toolCallId":"c","startedAtMs":"1","completedAtMs":"2""""
    )
  do
    test(s"metadata-only start has no tool name: $metadata"):
      val line =
        s"""{"type":"tool_call","subtype":"started","call_id":"c","tool_call":{$metadata}}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.Unknown("tool_call/started")
      )

    test(s"metadata-only completion has no tool name: $metadata"):
      val line =
        s"""{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{$metadata}}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolCompleted(None, true, line)
      )

  for subtype <- List("started", "completed") do
    test(s"$subtype still requires call_id"):
      val line =
        s"""{"type":"tool_call","subtype":"$subtype","tool_call":{"editToolCall":{}}}"""
      intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException](
        InboundEvent.parse(line)
      )

  for toolCall <- List("", ",\"tool_call\":null", ",\"tool_call\":{}") do
    test(s"start without tool members is unknown: $toolCall"):
      val line =
        s"""{"type":"tool_call","subtype":"started","call_id":"c"$toolCall}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.Unknown("tool_call/started")
      )

    test(s"completion without tool members has no name: $toolCall"):
      val line =
        s"""{"type":"tool_call","subtype":"completed","call_id":"c"$toolCall}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolCompleted(None, true, line)
      )

  test("unknown tool subtype ignores a payload with a different schema"):
    assertEquals(
      InboundEvent.parse(
        """{"type":"tool_call","subtype":"future","tool_call":42}"""
      ),
      InboundEvent.Unknown("tool_call/future")
    )

  for (fields, ok) <- List(
      "" -> true,
      "\"error\":null" -> true,
      "\"error\":false" -> false,
      "\"error\":{}" -> false,
      "\"error\":\"failed\"" -> false,
      "\"is_error\":null" -> true,
      "\"is_error\":false" -> true,
      "\"error\":null,\"is_error\":false" -> true,
      "\"error\":null,\"is_error\":true" -> false,
      "\"error\":{},\"is_error\":false" -> false,
      "\"error\":{},\"is_error\":true" -> false
    )
  do
    test(s"completed tool status preserves $fields"):
      val line =
        s"""{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{"shell":{"result":{$fields}}}}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolCompleted(Some("shell"), ok, line)
      )

  for result <- List("", ",\"result\":null") do
    test(s"completed tool tolerates absent or null result: $result"):
      val line =
        s"""{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{"shell":{"args":{}$result}}}"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolCompleted(Some("shell"), true, line)
      )

  test("unknown result outcomes and heterogeneous metadata are ignored"):
    val line =
      """{ "type":"tool_call", "subtype":"completed", "call_id":"c", "metadata":[null,42], "tool_call":{"shell":{"args":[1],"metadata":false,"result":{"success":{"output":"ok"},"future":[false,42],"metadata":"v2","is_error":false}}} }"""
    assertEquals(
      InboundEvent.parse(line),
      InboundEvent.ToolCompleted(Some("shell"), true, line)
    )

  test("unselected heterogeneous tool entries are not decoded as results"):
    val line =
      """{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{"shell":{"result":{"is_error":true}},"future":42}}"""
    assertEquals(
      InboundEvent.parse(line),
      InboundEvent.ToolCompleted(Some("shell"), false, line)
    )

  test("named tool ignores unrelated objects with incompatible result shapes"):
    val line =
      """{"type":"tool_call","subtype":"completed","call_id":"c","tool_call":{"future":{"result":42},"editToolCall":{"result":{"success":{}}},"hookAdditionalContexts":{"result":false}}}"""
    assertEquals(
      InboundEvent.parse(line),
      InboundEvent.ToolCompleted(Some("editToolCall"), true, line)
    )

  for payload <- List(
      "42",
      "null",
      "[]",
      "{\"result\":42}",
      "{\"result\":{\"is_error\":\"future\"}}"
    )
  do
    test(
      s"started tool preserves raw payload without decoding its result: $payload"
    ):
      val line =
        s"""{ "type":"tool_call", "subtype":"started", "call_id":"c", "tool_call":{"shell":$payload} }"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolStarted("shell", line)
      )

  for payload <- List("42", "null", "[]", "{\"result\":42}") do
    test(s"metadata-bearing start preserves unchecked payload: $payload"):
      val line =
        s"""{ "type":"tool_call", "subtype":"started", "call_id":"c", "tool_call":{"hookAdditionalContexts":[],"editToolCall":$payload,"startedAtMs":"1"} }"""
      assertEquals(
        InboundEvent.parse(line),
        InboundEvent.ToolStarted("editToolCall", line)
      )

  test("unknown event type bypasses heterogeneous tool payloads"):
    assertEquals(
      InboundEvent.parse("""{"type":"future","tool_call":42,"result":false}"""),
      InboundEvent.Unknown("future")
    )

private[cursor] object CursorToolFixtures:
  val completed: List[String] = List(
    """{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"editToolCall":{"args":{"path":"probe.txt"},"result":{"success":{}}},"hookAdditionalContexts":[],"toolCallId":"c1","startedAtMs":"c1","completedAtMs":"c1"}}""",
    """{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"hookAdditionalContexts":[],"toolCallId":"c1","startedAtMs":"c1","completedAtMs":"c1","editToolCall":{"args":{"path":"probe.txt"},"result":{"success":{}}}}}""",
    """{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"toolCallId":"c1","startedAtMs":"c1","completedAtMs":"c1","editToolCall":{"args":{"path":"probe.txt"},"result":{"success":{}}},"hookAdditionalContexts":[]}}""",
    """{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"startedAtMs":"c1","completedAtMs":"c1","editToolCall":{"args":{"path":"probe.txt"},"result":{"success":{}}},"hookAdditionalContexts":[],"toolCallId":"c1"}}""",
    """{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"completedAtMs":"c1","editToolCall":{"args":{"path":"probe.txt"},"result":{"success":{}}},"hookAdditionalContexts":[],"toolCallId":"c1","startedAtMs":"c1"}}"""
  )
