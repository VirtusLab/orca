package orca.backend

/** Asserts the [[TurnEvent]] message grammar (see the enum's scaladoc) over a
  * recorded event sequence. Shared by every backend's scripted turn tests via
  * `tools % "test->test"`.
  *
  * The three invariants checked here mirror the contract verbatim:
  *   - no empty messages — an `AssistantMessageEnd` must be preceded by
  *     assistant activity (`AssistantTextDelta` / `AssistantThinkingDelta` /
  *     `AssistantToolCall` / `ToolResult`) since the previous message end;
  *   - `ToolResult.toolName` is never `Some("")` — an absent name is `None`;
  *   - when the scenario settled (`completedNormally = true`), no assistant
  *     activity may trail the sequence without a closing `AssistantMessageEnd`.
  */
object TurnEventConformance extends munit.Assertions:

  /** @param completedNormally
    *   the scripted scenario settled (success or failure), as opposed to an
    *   abnormal mid-message kill where a trailing open message is legal.
    */
  def assertGrammar(
      events: List[TurnEvent],
      completedNormally: Boolean
  ): Unit =
    var activitySinceMessageEnd = false
    events.foreach:
      case TurnEvent.ToolResult(Some(""), _, _) =>
        fail(s"ToolResult.toolName must be None, never Some(\"\"), in: $events")
      case TurnEvent.AssistantMessageEnd =>
        assert(
          activitySinceMessageEnd,
          s"AssistantMessageEnd with no assistant activity since the last one (empty message) in: $events"
        )
        activitySinceMessageEnd = false
      // Activity vs. neutral routes through TurnEvent.opensMessage, the
      // exhaustive, single-source-of-truth classifier shared with the funnel
      // (DecodedTurn.Reader.emitAll).
      case e if e.opensMessage => activitySinceMessageEnd = true
      case _                   => ()
    if completedNormally && activitySinceMessageEnd then
      fail(
        s"scenario completed normally but the final message had activity with no AssistantMessageEnd in: $events"
      )
