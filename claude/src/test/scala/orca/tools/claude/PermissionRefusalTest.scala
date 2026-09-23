package orca.tools.claude

class PermissionRefusalTest extends munit.FunSuite:

  test("extracts the tool name from a refusal"):
    assertEquals(
      PermissionRefusal.toolName(
        "Claude requested permissions to use mcp__visdom__agents_md, " +
          "but you haven't granted it yet."
      ),
      Some("mcp__visdom__agents_md")
    )

  test("returns None when the phrase is quoted inside other output"):
    assertEquals(
      PermissionRefusal.toolName(
        "app.log:12: Claude requested permissions to use Bash, " +
          "but you haven't granted it yet.\napp.log:13: done"
      ),
      None
    )

  test("returns None for an unrelated mention of permissions"):
    assertEquals(
      PermissionRefusal.toolName("chmod: changing permissions of 'x': denied"),
      None
    )
