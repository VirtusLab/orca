package orca.backend.mcp

import orca.tools.Issue

class GitHubMcpServerTest extends munit.FunSuite:

  test("an issue body's markdown table reaches the agent with its rows intact"):
    val table = "| col |\n| --- |\n| cell |"
    val rendered = GitHubMcpServer.render(
      Issue(title = "t", body = table, author = "a", state = "open"),
      Nil
    )
    assert(rendered.contains(table), rendered)
