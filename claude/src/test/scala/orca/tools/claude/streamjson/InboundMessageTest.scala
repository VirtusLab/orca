package orca.tools.claude.streamjson

import orca.events.Usage
import orca.testkit.Usages.usage

class InboundMessageTest extends munit.FunSuite:

  test("system/init carries the session id out of the envelope"):
    val msg = InboundMessage.parse(
      """{"type":"system","subtype":"init","session_id":"sid-1"}"""
    )
    assertEquals(msg, InboundMessage.SystemInit("sid-1", None))

  test("system/init also surfaces the resolved model id when present"):
    val msg = InboundMessage.parse(
      """{"type":"system","subtype":"init","session_id":"sid-1","model":"claude-sonnet-4-6"}"""
    )
    assertEquals(
      msg,
      InboundMessage.SystemInit("sid-1", Some("claude-sonnet-4-6"))
    )

  test("assistant turn decodes every content block into the domain enum"):
    val msg = InboundMessage.parse(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hi"},{"type":"thinking","thinking":"ponder"}]}}"""
    )
    assertEquals(
      msg,
      InboundMessage.AssistantTurn(
        List(ContentBlock.Text("hi"), ContentBlock.Thinking("ponder")),
        None
      )
    )

  // The driver counts a turn's model responses by their distinct ids, so the id
  // has to survive parsing.
  test("assistant turn carries the id of the response it came from"):
    val msg = InboundMessage.parse(
      """{"type":"assistant","message":{"id":"msg_01ab","role":"assistant","content":[{"type":"text","text":"hi"}]}}"""
    )
    assertEquals(
      msg.asInstanceOf[InboundMessage.AssistantTurn].messageId,
      Some("msg_01ab")
    )

  test("result picks up structured_output as raw JSON + tallies usage"):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-1","structured_output":{"answer":42},"usage":{"input_tokens":10,"output_tokens":20},"total_cost_usd":0.003}"""
    )
    val r = msg.asInstanceOf[InboundMessage.Result]
    assertEquals(r.sessionId, "sid-1")
    assertEquals(r.structuredOutput, Some("""{"answer":42}"""))
    assertEquals(r.usage, Some(usage(10L, 20L, Some(BigDecimal("0.003")))))
    assertEquals(r.isError, false)

  test("result keeps cache creation and cache reads on separate axes"):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-1","usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":300,"cache_read_input_tokens":4000},"total_cost_usd":0.5}"""
    )
    assertEquals(
      msg.asInstanceOf[InboundMessage.Result].usage,
      Some(
        Usage(
          // The wire's `input_tokens` excludes both cache categories, so it is
          // the fresh axis as it stands.
          freshInputTokens = 10L,
          cacheReadInputTokens = 4000L,
          cacheWriteInputTokens = 300L,
          outputTokens = 20L,
          reasoningOutputTokens = 0L,
          cost = Some(BigDecimal("0.5")),
          apiCalls = None
        )
      )
    )

  // `num_turns` is the obvious-looking call count and is the wrong one — it
  // counts tool calls, not requests. The driver must not pick it up here.
  test("result ignores num_turns: the call count is not the parser's to set"):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-1","num_turns":4,"usage":{"input_tokens":10,"output_tokens":20}}"""
    )
    assertEquals(
      msg.asInstanceOf[InboundMessage.Result].usage.flatMap(_.apiCalls),
      None
    )

  test("result surfaces the resolved model id when present"):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-1","model":"claude-opus-4-7"}"""
    )
    assertEquals(
      msg.asInstanceOf[InboundMessage.Result].model,
      Some("claude-opus-4-7")
    )

  test("control_request delegates body parsing to ControlRequestBody"):
    val msg = InboundMessage.parse(
      """{"type":"control_request","request_id":"req-7","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"path":"/tmp/x"}}}"""
    )
    assertEquals(
      msg,
      InboundMessage.ControlRequest(
        "req-7",
        ControlRequestBody.CanUseTool("Read", """{"path":"/tmp/x"}""")
      )
    )

  test("stream_event delegates payload parsing to StreamEventPayload"):
    val msg = InboundMessage.parse(
      """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}"""
    )
    assertEquals(
      msg,
      InboundMessage.StreamEvent(StreamEventPayload.TextDelta(0, "lo"))
    )

  test("unknown top-level type collapses to Unknown(rawType)"):
    val msg = InboundMessage.parse("""{"type":"heartbeat"}""")
    assertEquals(msg, InboundMessage.Unknown("heartbeat"))

  test("assistant turn with empty content decodes to an empty block list"):
    val msg = InboundMessage.parse(
      """{"type":"assistant","message":{"role":"assistant","content":[]}}"""
    )
    assertEquals(msg, InboundMessage.AssistantTurn(Nil, None))

  test(
    "result with all optional fields absent leaves usage absent and isError false"
  ):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-x"}"""
    )
    val r = msg.asInstanceOf[InboundMessage.Result]
    assertEquals(r.output, None)
    assertEquals(r.structuredOutput, None)
    assertEquals(r.usage, None)
    assertEquals(r.isError, false)

  test("non-init system subtype is namespaced into Unknown"):
    val msg = InboundMessage.parse(
      """{"type":"system","subtype":"keepalive"}"""
    )
    assertEquals(msg, InboundMessage.Unknown("system.keepalive"))
