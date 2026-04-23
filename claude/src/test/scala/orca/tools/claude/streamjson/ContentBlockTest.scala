package orca.tools.claude.streamjson

class ContentBlockTest extends munit.FunSuite:

  test("text block decodes into Text with the literal string"):
    val block = ContentBlock.parse("""{"type":"text","text":"hello"}""")
    assertEquals(block, ContentBlock.Text("hello"))

  test("thinking block pulls the `thinking` field, not `text`"):
    val block = ContentBlock.parse("""{"type":"thinking","thinking":"hmm"}""")
    assertEquals(block, ContentBlock.Thinking("hmm"))

  test("tool_use preserves the input subtree verbatim as raw JSON"):
    val block = ContentBlock.parse(
      """{"type":"tool_use","id":"id-1","name":"Bash","input":{"cmd":"ls","flags":["-la"]}}"""
    )
    assertEquals(
      block,
      ContentBlock.ToolUse(
        id = "id-1",
        name = "Bash",
        rawInput = """{"cmd":"ls","flags":["-la"]}"""
      )
    )

  test("tool_result captures the content string and error flag"):
    val block = ContentBlock.parse(
      """{"type":"tool_result","tool_use_id":"id-1","content":"ok","is_error":false}"""
    )
    assertEquals(
      block,
      ContentBlock.ToolResult("id-1", "ok", isError = false)
    )

  test("unknown block type collapses to Unknown(rawType)"):
    val block = ContentBlock.parse("""{"type":"image","source":{}}""")
    assertEquals(block, ContentBlock.Unknown("image"))

  test("tool_result with is_error missing defaults to false"):
    val block = ContentBlock.parse(
      """{"type":"tool_result","tool_use_id":"id-2","content":"ran"}"""
    )
    assertEquals(block, ContentBlock.ToolResult("id-2", "ran", isError = false))

  test("non-ASCII text round-trips through tool_use raw JSON intact"):
    val block = ContentBlock.parse(
      """{"type":"tool_use","id":"id-3","name":"Write","input":{"note":"café"}}"""
    )
    assertEquals(
      block,
      ContentBlock.ToolUse("id-3", "Write", """{"note":"café"}""")
    )
