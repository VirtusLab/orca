package orca.backend

import orca.agents.{AgentConfig, ToolSet}

class SystemPromptComposerTest extends munit.FunSuite:

  private val gitRule = SystemPromptComposer.RuntimeOwnsGit
  private val readOnlyRule = SystemPromptComposer.ReadOnlyTurn
  private val backgroundRule =
    SystemPromptComposer.BackgroundWorkAbandonedAtTurnEnd

  test("write-capable turn with nothing else gets the git rule"):
    val out = SystemPromptComposer.combine(AgentConfig(), None)
    assertEquals(out, s"$gitRule\n\n$backgroundRule")

  test("read-only turn gets the read-only rule instead of the git rule"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.ReadOnly),
      None
    )
    assertEquals(out, s"$readOnlyRule\n\n$backgroundRule")

  test("network-only turn gets the read-only rule too"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(tools = ToolSet.NetworkOnly),
      None
    )
    assertEquals(out, s"$readOnlyRule\n\n$backgroundRule")

  test("the read-only rule says nothing about the network"):
    // A NetworkOnly turn is given read-only network access on purpose; the
    // shared rule must not take it back.
    assert(!readOnlyRule.toLowerCase.contains("network"), readOnlyRule)

  test("read-only config keeps its systemPrompt ahead of the standing rules"):
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(
        systemPrompt = Some("be terse"),
        tools = ToolSet.ReadOnly
      ),
      extraHint = None
    )
    assertEquals(out, s"be terse\n\n$readOnlyRule\n\n$backgroundRule")

  test("selfManagedGit omits the git rule but keeps the background rule"):
    // With this flag the runtime stays out of the agent's git, so the agent may
    // commit/push itself — but who drives git says nothing about what survives
    // the turn boundary, so the background rule still applies.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(selfManagedGit = true),
      extraHint = None
    )
    assertEquals(out, backgroundRule)

  test("foldIntoPrompt keeps a user-prompt line that starts with `|`"):
    val out = SystemPromptComposer.foldIntoPrompt(
      AgentConfig(),
      userPrompt = "review this:\n |context with pipe"
    )
    assert(out.endsWith("review this:\n |context with pipe"), out)

  test("joins config + hint + both rules with blank lines, in order"):
    // Backends rely on the blank-line separator so the agent reads distinct paragraphs.
    val out = SystemPromptComposer.combine(
      AgentConfig().copy(systemPrompt = Some("be terse")),
      extraHint = Some("the hint")
    )
    assertEquals(out, s"be terse\n\nthe hint\n\n$gitRule\n\n$backgroundRule")
