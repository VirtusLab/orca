package orca.tools.claude.streamjson

class OutboundMessageTest extends munit.FunSuite:

  test(
    "userText serializes as {type: user, message: {role: user, content: [text block]}}"
  ):
    assertEquals(
      OutboundMessage.userText("hello"),
      """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}"""
    )
