package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.BackendTag

/** Drains a [[Conversation]] for the autonomous path, mapping conversation
  * events to [[OrcaEvent]]s and returning the awaited `AgentResult`.
  *
  * Structured mode (`conv.outputSchema.isDefined`) withholds the last assistant
  * turn so the closing JSON payload doesn't surface as an `AssistantMessage` —
  * the caller emits it via `OrcaEvent.StructuredResult` instead. Outside
  * structured mode every turn flushes; end-of-stream flushes any unfinished
  * buffer so a mid-turn crash doesn't lose partial output.
  *
  * Interactive-only events that nevertheless reach this drain are handled
  * explicitly rather than dropped: `ApproveTool` is auto-denied (the subprocess
  * would otherwise block waiting for a response), and `UserQuestion` is
  * auto-answered with a placeholder for the same reason. Both also surface as
  * `OrcaEvent.Error` so the user sees what happened.
  *
  * `Left(OrcaInteractiveCancelled)` from `awaitResult()` is rethrown — the
  * autonomous shape never exposes a cancel button to the caller.
  */
private[orca] object Conversations:

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      events: OrcaListener = OrcaListener.noop
  ): AgentResult[B] =
    val structuredMode = conv.outputSchema.isDefined
    val textBuf = new StringBuilder
    // Previously-closed turn's text, kept around in structured mode while we
    // wait to see if it's followed by another turn.
    var withheld: Option[String] = None
    def closeTurn(): Unit =
      if textBuf.nonEmpty then
        val text = textBuf.toString
        textBuf.clear()
        if structuredMode then
          withheld.foreach(p => events.onEvent(OrcaEvent.AssistantMessage(p)))
          withheld = Some(text)
        else events.onEvent(OrcaEvent.AssistantMessage(text))
    try
      conv.events.foreach:
        case ConversationEvent.AssistantToolCall(name, raw) =>
          events.onEvent(OrcaEvent.ToolUse(name, raw))
        case ConversationEvent.AssistantTextDelta(delta) =>
          val _ = textBuf.append(delta)
        case ConversationEvent.AssistantThinkingDelta(_) => ()
        case ConversationEvent.AssistantTurnEnd          => closeTurn()
        case ConversationEvent.Error(msg) =>
          events.onEvent(OrcaEvent.Error(msg))
        case ConversationEvent.ApproveTool(toolName, _, respond) =>
          // The subprocess is blocking on stdin waiting for our decision;
          // autonomous mode has no user to ask. Deny with an explanatory
          // reason (so the agent can adapt) and surface the denial as a
          // visible error — silently dropping would deadlock the call.
          respond(
            ApprovalDecision.Deny(
              Some(
                s"$toolName is not in the auto-approve set and " +
                  "autonomous mode cannot prompt for permission"
              )
            )
          )
          events.onEvent(
            OrcaEvent.Error(
              s"Denied $toolName: not in auto-approve set " +
                "(autonomous mode cannot prompt)"
            )
          )
        case ConversationEvent.UserQuestion(_, respond) =>
          // Defensive: in autonomous mode the ask_user MCP bridge isn't
          // wired (see `SessionMode.Autonomous`), so this event should be
          // unreachable. If a future change ever lands one here, the
          // bridge thread is blocked on `respond` — unblock it instead of
          // leaking the thread. The synthetic answer surfaces in the
          // tool result the agent receives.
          respond("[autonomous mode: no user available to answer]")
          events.onEvent(
            OrcaEvent.Error(
              "ask_user fired during an autonomous call; auto-answered"
            )
          )
        case ConversationEvent.UserMessage(_) =>
          // Wire-level echo of input we already sent. Surfaced upstream as
          // `OrcaEvent.UserPrompt` from the Agent layer; nothing to do
          // here.
          ()
        case ConversationEvent.ToolResult(_, _, _) =>
          // Tool output volume is unbounded (full cargo-test logs, etc.).
          // The matching `AssistantToolCall` already surfaced as
          // `OrcaEvent.ToolUse`; the agent's follow-up turn summarises the
          // outcome. Listeners that need raw output should subscribe at
          // the `ConversationEvent` layer instead.
          ()
    finally
      // try-body may have thrown mid-turn; flush what we can. Class scaladoc
      // explains the structured-mode drop.
      closeTurn()
      if !structuredMode then
        withheld.foreach(p => events.onEvent(OrcaEvent.AssistantMessage(p)))
    conv.awaitResult() match
      case Right(result) => result
      // Autonomous callers can't produce a Left; surface as a throw so the
      // AgentResult call shape is honoured.
      case Left(cancelled) => throw cancelled
