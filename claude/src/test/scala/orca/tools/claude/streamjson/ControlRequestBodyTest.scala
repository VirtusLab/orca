package orca.tools.claude.streamjson

class ControlRequestBodyTest extends munit.FunSuite:

  test("can_use_tool exposes the tool name and raw input JSON"):
    val body = ControlRequestBody.parse(
      """{"subtype":"can_use_tool","tool_name":"Bash","input":{"cmd":"ls -la"}}"""
    )
    assertEquals(
      body,
      ControlRequestBody.CanUseTool("Bash", """{"cmd":"ls -la"}""")
    )

  test("unknown subtype collapses to Unknown"):
    val body = ControlRequestBody.parse(
      """{"subtype":"permission_mode_change","mode":"plan"}"""
    )
    assertEquals(body, ControlRequestBody.Unknown("permission_mode_change"))
