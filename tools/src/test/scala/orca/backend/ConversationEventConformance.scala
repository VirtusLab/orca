package orca.backend

/** Asserts the [[ConversationEvent]] grammar (see the enum's scaladoc) over a
  * recorded event sequence. Shared by every backend's scripted conversation
  * tests via `tools % "test->test"`.
  *
  * The three invariants checked here mirror the contract verbatim:
  *   - no empty turns — an `AssistantTurnEnd` must be preceded by assistant
  *     activity (`AssistantTextDelta` / `AssistantThinkingDelta` /
  *     `AssistantToolCall` / `ToolResult`) since the previous turn end;
  *   - `ToolResult.toolName` is never `Some("")` — an absent name is `None`;
  *   - when the scenario settled (`completedNormally = true`), no assistant
  *     activity may trail the sequence without a closing `AssistantTurnEnd`.
  */
object ConversationEventConformance extends munit.Assertions:

  /** @param completedNormally
    *   the scripted scenario settled (success or failure), as opposed to an
    *   abnormal mid-turn kill where a trailing open turn is legal.
    */
  def assertGrammar(
      events: List[ConversationEvent],
      completedNormally: Boolean
  ): Unit =
    var activitySinceTurnEnd = false
    events.foreach:
      case ConversationEvent.ToolResult(Some(""), _, _) =>
        fail(s"ToolResult.toolName must be None, never Some(\"\"), in: $events")
      case ConversationEvent.AssistantTextDelta(_) |
          ConversationEvent.AssistantThinkingDelta(_) |
          ConversationEvent.AssistantToolCall(_, _) |
          ConversationEvent.ToolResult(_, _, _) =>
        activitySinceTurnEnd = true
      case ConversationEvent.AssistantTurnEnd =>
        assert(
          activitySinceTurnEnd,
          s"AssistantTurnEnd with no assistant activity since the last turn end (empty turn) in: $events"
        )
        activitySinceTurnEnd = false
      case _ => ()
    if completedNormally && activitySinceTurnEnd then
      fail(
        s"scenario completed normally but the final turn had activity with no AssistantTurnEnd in: $events"
      )
