package orca.backend

import orca.agents.{AgentConfig, ToolSet}

class SystemPromptComposerTest extends munit.FunSuite:

  private val gitRule = SystemPromptComposer.RuntimeOwnsGit

  test("write-capable turn with nothing else gets just the runtime-git rule"):
    val out = SystemPromptComposer.combine(AgentConfig.default, None)
    assertEquals(out, Some(gitRule))

  test("read-only turn with neither config nor hint returns None"):
    // Read-only turns can't commit, so the git rule is omitted — and with no
    // systemPrompt or hint there's nothing left to compose.
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(tools = ToolSet.ReadOnly),
      None
    )
    assertEquals(out, None)

  test("network-only turn also omits the git rule (not Full)"):
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(tools = ToolSet.NetworkOnly),
      None
    )
    assertEquals(out, None)

  test("config systemPrompt precedes the appended runtime-git rule"):
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(systemPrompt = Some("be terse")),
      extraHint = None
    )
    assertEquals(out, Some(s"be terse\n\n$gitRule"))

  test("read-only config keeps just its systemPrompt (no git rule)"):
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(
        systemPrompt = Some("be terse"),
        tools = ToolSet.ReadOnly
      ),
      extraHint = None
    )
    assertEquals(out, Some("be terse"))

  test("selfManagedGit escape hatch omits the git rule"):
    // `agent.withSelfManagedGit` flips this flag; the runtime then stays out of
    // the agent's git so the agent may commit/push itself.
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(selfManagedGit = true),
      extraHint = None
    )
    assertEquals(out, None)

  test("joins config + hint + git rule with blank lines, in order"):
    // Pins both the order (config, hint, rule) and the separator (\\n\\n) —
    // backends rely on blank lines so the agent reads distinct paragraphs.
    val out = SystemPromptComposer.combine(
      AgentConfig.default.copy(systemPrompt = Some("be terse")),
      extraHint = Some("the hint")
    )
    assertEquals(out, Some(s"be terse\n\nthe hint\n\n$gitRule"))
