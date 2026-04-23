package orca.tools.claude.streamjson

class StreamEventPayloadTest extends munit.FunSuite:

  test("text_delta surfaces index and appended text"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hel"}}"""
    )
    assertEquals(payload, StreamEventPayload.TextDelta(0, "hel"))

  test("thinking_delta reads the `thinking` field, not `text`"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"..."}}"""
    )
    assertEquals(payload, StreamEventPayload.ThinkingDelta(1, "..."))

  test("input_json_delta preserves the partial JSON chunk verbatim"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"l"}}"""
    )
    assertEquals(payload, StreamEventPayload.InputJsonDelta(2, """{"cmd":"l"""))

  test("content_block_start wraps the underlying block"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}"""
    )
    assertEquals(
      payload,
      StreamEventPayload.ContentBlockStart(3, ContentBlock.Text(""))
    )

  test("content_block_stop carries the index"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_stop","index":4}"""
    )
    assertEquals(payload, StreamEventPayload.ContentBlockStop(4))

  test("message_start is ignorable — driver has nothing to render"):
    val payload = StreamEventPayload.parse(
      """{"type":"message_start","message":{}}"""
    )
    assertEquals(payload, StreamEventPayload.Unhandled("message_start"))

  test("unknown delta subtype is ignorable with a namespaced label"):
    val payload = StreamEventPayload.parse(
      """{"type":"content_block_delta","index":0,"delta":{"type":"magic_delta","value":"x"}}"""
    )
    assertEquals(
      payload,
      StreamEventPayload.Unhandled("content_block_delta.magic_delta")
    )
