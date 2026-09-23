package orca.agents

class NetworkToolsTest extends munit.FunSuite:

  test("the old command-scoped syntax is rejected"):
    // --tools drops a name it doesn't recognise silently, so a flow script
    // still passing `Bash(gh api:*)` would grant nothing and say nothing.
    val thrown = intercept[IllegalArgumentException]:
      NetworkTools(Seq("WebFetch", "Bash(gh api:*)"))
    assert(thrown.getMessage.contains("Bash(gh api:*)"), thrown.getMessage)

  test("a write-capable builtin is rejected"):
    // A bare "Bash" passes the shape check.
    val thrown = intercept[IllegalArgumentException]:
      NetworkTools(Seq("WebFetch", "Bash"))
    assert(thrown.getMessage.contains("Bash"), thrown.getMessage)
    assert(thrown.getMessage.contains("ToolSet.Full"), thrown.getMessage)
