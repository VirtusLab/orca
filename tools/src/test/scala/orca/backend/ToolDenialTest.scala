package orca.backend

class ToolDenialTest extends munit.FunSuite:

  test("extracts the tool name from claude's permission refusal"):
    assertEquals(
      ToolDenial.fromToolResult(
        "Claude requested permissions to use mcp__visdom__agents_md, " +
          "but you haven't granted it yet."
      ),
      Some("mcp__visdom__agents_md")
    )

  test("returns None when the phrase is quoted inside other output"):
    assertEquals(
      ToolDenial.fromToolResult(
        "app.log:12: Claude requested permissions to use Bash, " +
          "but you haven't granted it yet.\napp.log:13: done"
      ),
      None
    )

  test("returns None for an unrelated mention of permissions"):
    assertEquals(
      ToolDenial.fromToolResult("chmod: changing permissions of 'x': denied"),
      None
    )
