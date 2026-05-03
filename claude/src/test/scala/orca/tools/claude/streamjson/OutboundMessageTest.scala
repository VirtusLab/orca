package orca.tools.claude.streamjson

class OutboundMessageTest extends munit.FunSuite:

  test(
    "UserText serializes as {type: user, message: {role: user, content: [text block]}}"
  ):
    val json = OutboundMessage.toJson(OutboundMessage.UserText("hello"))
    assertEquals(
      json,
      """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}"""
    )

  test("ControlResponse allow without updatedInput emits behavior: allow"):
    val json = OutboundMessage.toJson(
      OutboundMessage.ControlResponse("req-1", ControlDecision.Allow())
    )
    assertEquals(
      json,
      """{"type":"control_response","response":{"subtype":"can_use_tool_response","request_id":"req-1","behavior":"allow"}}"""
    )

  test("ControlResponse allow with updatedInput embeds the raw JSON"):
    val json = OutboundMessage.toJson(
      OutboundMessage.ControlResponse(
        "req-1",
        ControlDecision.Allow(Some("""{"cmd":"rm -rf /"}"""))
      )
    )
    assert(json.contains(""""behavior":"allow""""))
    assert(json.contains(""""updatedInput":{"cmd":"rm -rf /"}"""))

  test(
    "ControlResponse deny with reason sets behavior: deny and carries the message"
  ):
    val json = OutboundMessage.toJson(
      OutboundMessage.ControlResponse(
        "req-2",
        ControlDecision.Deny(Some("tool not in allow-list"))
      )
    )
    assertEquals(
      json,
      """{"type":"control_response","response":{"subtype":"can_use_tool_response","request_id":"req-2","behavior":"deny","message":"tool not in allow-list"}}"""
    )
