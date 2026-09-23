package orca.tools.claude

class ClaudeNetworkToolsTest extends munit.FunSuite:

  test("a command-scoped entry is rejected"):
    // --tools drops a name it doesn't recognise silently, so `Bash(gh api:*)`
    // would grant nothing and say nothing.
    val thrown = intercept[IllegalArgumentException]:
      ClaudeNetworkTools.validated(Seq("WebFetch", "Bash(gh api:*)"))
    assert(thrown.getMessage.contains("Bash(gh api:*)"), thrown.getMessage)

  test("a write-capable builtin is rejected"):
    // A bare "Bash" passes the shape check.
    val thrown = intercept[IllegalArgumentException]:
      ClaudeNetworkTools.validated(Seq("WebFetch", "Bash"))
    assert(thrown.getMessage.contains("Bash"), thrown.getMessage)
    assert(thrown.getMessage.contains("ToolSet.Full"), thrown.getMessage)
