package orca.tools.gemini.jsonl

class InboundEventTest extends munit.FunSuite:

  test("parses init event into Init(sessionId, model)"):
    val line =
      """{"type":"init","session_id":"abc123","model":"gemini-2.5-pro"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Init(sid, model) =>
        assertEquals(sid, "abc123")
        assertEquals(model, Some("gemini-2.5-pro"))
      case other => fail(s"expected Init, got $other")

  test("init tolerates a missing model"):
    val line = """{"type":"init","session_id":"s1"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Init(sid, model) =>
        assertEquals(sid, "s1")
        assertEquals(model, None)
      case other => fail(s"expected Init, got $other")

  test("init with a missing session_id throws rather than defaulting to \"\""):
    // Identity-critical: a missing session_id must not silently become Init("");
    // it propagates JsonReaderException so the reader's generic catch surfaces a
    // visible Error event near the cause.
    val line = """{"type":"init","model":"gemini-2.5-pro"}"""
    intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException]:
      InboundEvent.parse(line)

  test("parses a message event faithfully (role + content)"):
    val line = """{"type":"message","role":"user","content":"List files"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Message(role, content) =>
        assertEquals(role, Role.User)
        assertEquals(content, "List files")
      case other => fail(s"expected Message, got $other")

  test("a non-user role is classified Assistant"):
    val line = """{"type":"message","role":"model","content":"hi"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Message(role, _) => assertEquals(role, Role.Assistant)
      case other => fail(s"expected Message, got $other")

  test("a missing role is classified Unknown, not defaulted to a role string"):
    val line = """{"type":"message","content":"stray"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Message(role, content) =>
        assertEquals(role, Role.Unknown)
        assertEquals(content, "stray")
      case other => fail(s"expected Message, got $other")

  test("parses a tool_use event"):
    val line =
      """{"type":"tool_use","tool_name":"Bash","tool_id":"b-1","parameters":{"command":"ls"}}"""
    InboundEvent.parse(line) match
      case InboundEvent.ToolUse(name, id, params) =>
        assertEquals(name, "Bash")
        assertEquals(id, "b-1")
        assertEquals(params, """{"command":"ls"}""")
      case other => fail(s"expected ToolUse, got $other")

  test("parses a tool_result event"):
    val line =
      """{"type":"tool_result","tool_id":"b-1","status":"success","output":"file1\nfile2"}"""
    InboundEvent.parse(line) match
      case InboundEvent.ToolResult(id, status, output) =>
        assertEquals(id, "b-1")
        assertEquals(status, "success")
        assertEquals(output, "file1\nfile2")
      case other => fail(s"expected ToolResult, got $other")

  test("parses an error event"):
    val line = """{"type":"error","message":"rate limited"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Error(msg) => assertEquals(msg, "rate limited")
      case other                   => fail(s"expected Error, got $other")

  test("parses result event token stats into Usage"):
    val line =
      """{"type":"result","status":"success","stats":{"total_tokens":100,"input_tokens":50,"output_tokens":50,"duration_ms":1200,"tool_calls":1}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(usage, status) =>
        assertEquals(status, "success")
        assertEquals(usage.inputTokens, 50L)
        assertEquals(usage.outputTokens, 50L)
        assertEquals(usage.cost, None)
      case other => fail(s"expected Result, got $other")

  test("result maps the wire `cached` field into cachedInputTokens"):
    // gemini 0.45.2 spells the cache sub-count `cached` (not
    // `cached_input_tokens`) inside stats.
    val line =
      """{"type":"result","status":"success","stats":{"input_tokens":9954,"output_tokens":1,"cached":120}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(usage, _) =>
        assertEquals(usage.cachedInputTokens, 120L)
      case other => fail(s"expected Result, got $other")

  test("result falls back to `cached_input_tokens` when `cached` is absent"):
    val line =
      """{"type":"result","status":"success","stats":{"input_tokens":9,"output_tokens":1,"cached_input_tokens":77}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(usage, _) =>
        assertEquals(usage.cachedInputTokens, 77L)
      case other => fail(s"expected Result, got $other")

  test("result prefers `cached` over `cached_input_tokens` when both present"):
    val line =
      """{"type":"result","status":"success","stats":{"input_tokens":9,"output_tokens":1,"cached":120,"cached_input_tokens":77}}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(usage, _) =>
        assertEquals(usage.cachedInputTokens, 120L)
      case other => fail(s"expected Result, got $other")

  test("result tolerates missing stats"):
    val line = """{"type":"result","status":"success"}"""
    InboundEvent.parse(line) match
      case InboundEvent.Result(usage, _) =>
        assertEquals(usage.inputTokens, 0L)
        assertEquals(usage.outputTokens, 0L)
      case other => fail(s"expected Result, got $other")

  test("unknown top-level type collapses to Unknown"):
    val line = """{"type":"heartbeat","ts":123}"""
    InboundEvent.parse(line) match
      case InboundEvent.Unknown(t) => assertEquals(t, "heartbeat")
      case other                   => fail(s"expected Unknown, got $other")
