package orca.backend

import orca.agents.{AgentConfig, ToolSet}

class SystemPromptComposerTest extends munit.FunSuite:

  private val gitRule = SystemPromptComposer.RuntimeOwnsGit
  private val backgroundRule =
    SystemPromptComposer.BackgroundWorkAbandonedAtTurnEnd

  test("write-capable turn with nothing else gets both standing rules"):
    val out = SystemPromptComposer.combine(AgentConfig(), None)
    assertEquals(out, Some(s"$gitRule\n\n$backgroundRule"))

  test("read-only turn with nothing else gets the background rule alone"):
    // Read-only turns can't commit, so the git rule is omitted; the turn
    // boundary applies to them exactly as it does to a write-capable turn.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.ReadOnly),
      None
    )
    assertEquals(out, Some(backgroundRule))

  test("network-only turn also gets the background rule alone"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.NetworkOnly),
      None
    )
    assertEquals(out, Some(backgroundRule))

  test("read-only config keeps its systemPrompt and drops only the git rule"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(
        systemPrompt = Some("be terse"),
        tools = ToolSet.ReadOnly
      ),
      extraHint = None
    )
    assertEquals(out, Some(s"be terse\n\n$backgroundRule"))

  test("selfManagedGit omits the git rule but keeps the background rule"):
    // With this flag the runtime stays out of the agent's git, so the agent may
    // commit/push itself — but who drives git says nothing about what survives
    // the turn boundary, so the background rule still applies.
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
