package orca.tools.claude.streamjson

import orca.Usage

class InboundMessageTest extends munit.FunSuite:

  test("system/init carries the session id out of the envelope"):
    val msg = InboundMessage.parse(
      """{"type":"system","subtype":"init","session_id":"sid-1"}"""
    )
    assertEquals(msg, InboundMessage.SystemInit("sid-1"))

  test("assistant turn decodes every content block into the domain enum"):
    val msg = InboundMessage.parse(
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hi"},{"type":"thinking","thinking":"ponder"}]}}"""
    )
    assertEquals(
      msg,
      InboundMessage.AssistantTurn(
        List(ContentBlock.Text("hi"), ContentBlock.Thinking("ponder"))
      )
    )

  test("result picks up structured_output as raw JSON + tallies usage"):
    val msg = InboundMessage.parse(
      """{"type":"result","subtype":"success","session_id":"sid-1","structured_output":{"answer":42},"usage":{"input_tokens":10,"output_tokens":20},"total_cost_usd":0.003}"""
    )
    val r = msg.asInstanceOf[InboundMessage.Result]
    assertEquals(r.sessionId, "sid-1")
    assertEquals(r.structuredOutput, Some("""{"answer":42}"""))
    assertEquals(r.usage, Usage(10L, 20L, Some(BigDecimal("0.003"))))
    assertEquals(r.isError, false)

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
