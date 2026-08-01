package orca.backend

import orca.agents.{AgentConfig, ToolSet}

class SystemPromptComposerTest extends munit.FunSuite:

  private val gitRule = SystemPromptComposer.RuntimeOwnsGit
  private val backgroundRule = SystemPromptComposer.NoBackgroundWork

  test("write-capable turn with nothing else gets both standing rules"):
    val out = SystemPromptComposer.combine(AgentConfig(), None)
    assertEquals(out, Some(s"$gitRule\n\n$backgroundRule"))

  test("read-only turn with neither config nor hint returns None"):
    // Read-only turns can't commit, and they read and report rather than run
    // builds, so neither standing rule applies; nothing else to compose.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.ReadOnly),
      None
    )
    assertEquals(out, None)

  test("network-only turn also omits both rules (not Full)"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.NetworkOnly),
      None
    )
    assertEquals(out, None)

  test("config systemPrompt precedes the appended standing rules"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(systemPrompt = Some("be terse")),
      extraHint = None
    )
    assertEquals(out, Some(s"be terse\n\n$gitRule\n\n$backgroundRule"))

  test("read-only config keeps just its systemPrompt (no standing rules)"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(
        systemPrompt = Some("be terse"),
        tools = ToolSet.ReadOnly
      ),
      extraHint = None
    )
    assertEquals(out, Some("be terse"))

  test("selfManagedGit omits the git rule but keeps the background rule"):
    // With this flag the runtime stays out of the agent's git, so the agent may
    // commit/push itself — but who drives git says nothing about process
    // lifetime, so the background rule still applies.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(selfManagedGit = true),
      extraHint = None
    )
    assertEquals(out, Some(backgroundRule))

  test("joins config + hint + both rules with blank lines, in order"):
    // Backends rely on the blank-line separator so the agent reads distinct paragraphs.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(systemPrompt = Some("be terse")),
      extraHint = Some("the hint")
    )
    assertEquals(
      out,
      Some(s"be terse\n\nthe hint\n\n$gitRule\n\n$backgroundRule")
    )
