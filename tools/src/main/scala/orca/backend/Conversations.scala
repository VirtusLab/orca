package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{BackendTag, SessionId}

import ox.{Ox, supervised}

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

  /** The shared autonomous-turn finalize for the subprocess backends
    * (claude/codex/gemini/pi): drain the conversation, then — on success only —
    * commit the session as resumable. Returns the drained result verbatim. The
    * result carries the backend-reported wire id under its own
    * [[orca.agents.WireSessionId]] type, so there is nothing to re-stamp:
    * callers hand back the stable client handle they already hold, and the wire
    * id lives on the result only for the registry to learn the mapping.
    *
    * `commitSuccess` runs only after a clean drain, so a subprocess that
    * crashed before registering its session doesn't wedge the registry into
    * resuming a session that was never created. Drain failures propagate
    * verbatim — the retryability classification already happened in
    * [[ForkedConversation.awaitResult]], the sole place that decides whether a
    * failure is an [[orca.AgentTurnFailed]] or a plain retryable
    * [[orca.OrcaFlowException]]; relabelling here would only obscure that
    * decision.
    *
    * `registry.commitSuccess(session, result.wireId)` is uniform across both
    * registry shapes: [[SessionRegistry.ClientToServer]] records the learned
    * server id, while [[SessionRegistry.ClaimedOnce]] ignores the server arg
    * and just marks the client id claimed.
    */
  def drainAndCommit[B <: BackendTag](
      conv: Conversation[B],
      session: SessionId[B],
      registry: SessionRegistry[B],
      events: OrcaListener = OrcaListener.noop
  ): AgentResult[B] =
    val result = drainAutonomous(conv, events)
    registry.commitSuccess(session, result.wireId)
    result

  /** The complete autonomous-turn shell shared by all backends: open the
    * conversation inside its own supervised scope, drain + commit, and ALWAYS
    * cancel before the scope joins (the cancel is load-bearing on failure paths
    * — `drainAndCommit` does not tear down). `open` runs inside the scope so
    * the conversation's forks bind to it.
    */
  def runAutonomous[B <: BackendTag](
      session: SessionId[B],
      registry: SessionRegistry[B],
      events: OrcaListener
  )(open: Ox ?=> Conversation[B]): AgentResult[B] =
    supervised:
      val conv = open
      try drainAndCommit(conv, session, registry, events)
      finally conv.cancel()
