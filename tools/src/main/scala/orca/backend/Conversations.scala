package orca.backend

import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{BackendTag, SessionId, WireSessionId}

import ox.{Ox, supervised}

/** Drains a [[Conversation]] for the autonomous path, mapping conversation
  * events to [[OrcaEvent]]s and returning the awaited `AgentResult`.
  *
  * Structured mode (`conv.outputSchema.isDefined`) withholds the last assistant
  * turn so the closing JSON payload doesn't surface as an `AssistantMessage` —
  * the caller emits it via `OrcaEvent.StructuredResult` instead. Outside
  * structured mode every turn flushes immediately. The withheld-turn state
  * machine lives in [[TurnBuffer]]; a normal end drops the withheld payload and
  * flushes any unfinished buffer, while an abnormal (thrown) end flushes
  * everything — a mid-turn crash never silently loses partial output.
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

  /** Structured-mode turn buffer: streams every completed turn EXCEPT the most
    * recent one, which is withheld one turn (the final turn is the JSON payload
    * — the caller re-surfaces it via OrcaEvent.StructuredResult). Consequence:
    * turn N renders when turn N+1 completes — a deliberate one-turn display
    * delay, the price of live streaming without showing the payload as prose.
    * In non-structured mode every turn flushes immediately.
    *
    * All state is confined to one drain's single-threaded event loop: the drain
    * owns the instance and calls it from exactly one thread.
    */
  private final class TurnBuffer(structuredMode: Boolean, emit: String => Unit):
    private val current = new StringBuilder
    private var withheld: Option[String] = None

    def append(delta: String): Unit =
      val _ = current.append(delta)

    /** Close the current turn. Structured: emit the previously-withheld turn
      * and withhold this one (the one-turn delay); non-structured: emit now.
      */
    def turnEnd(): Unit =
      if current.nonEmpty then
        val text = current.toString
        current.clear()
        if structuredMode then
          withheld.foreach(emit)
          withheld = Some(text)
        else emit(text)

    /** Normal end of stream: the withheld turn IS the payload — drop it (the
      * caller emits StructuredResult); flush any unfinished current buffer. (In
      * non-structured mode `withheld` is always empty, so the drop is a no-op
      * and only the unfinished buffer flushes — matching the historical
      * end-of-stream behaviour.)
      */
    def finishNormally(): Unit =
      withheld = None
      flushCurrent()

    /** Abnormal end (the drain threw mid-stream): nothing here is reliably the
      * payload — flush EVERYTHING (withheld + partial) rather than silently
      * dropping prose. Worst case the user sees a JSON blob once.
      */
    def finishAbnormally(): Unit =
      withheld.foreach(emit)
      withheld = None
      flushCurrent()

    private def flushCurrent(): Unit =
      if current.nonEmpty then
        emit(current.toString)
        current.clear()

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      events: OrcaListener = OrcaListener.noop
  ): AgentResult[B] =
    val buffer = new TurnBuffer(
      conv.outputSchema.isDefined,
      text => events.onEvent(OrcaEvent.AssistantMessage(text))
    )
    try
      conv.events.foreach:
        case ConversationEvent.AssistantToolCall(name, raw) =>
          events.onEvent(OrcaEvent.ToolUse(name, raw))
        case ConversationEvent.AssistantTextDelta(delta) =>
          buffer.append(delta)
        case ConversationEvent.AssistantThinkingDelta(_) => ()
        case ConversationEvent.AssistantTurnEnd          => buffer.turnEnd()
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
      // The loop ran to completion: the withheld turn is the payload, drop it;
      // flush any unfinished buffer. See TurnBuffer.finishNormally.
      buffer.finishNormally()
    catch
      case t: Throwable =>
        // The loop threw mid-turn: nothing is reliably the payload, so flush
        // everything rather than dropping prose, then rethrow the original
        // failure. See TurnBuffer.finishAbnormally.
        buffer.finishAbnormally()
        throw t
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
    val wire = WireSessionId.value(result.wireId)
    if !SessionId.isSafe(wire) then
      // Plain OrcaFlowException, not AgentTurnFailed: this is retryable — a
      // fresh attempt may see a healthy init event from the backend, and
      // because we throw BEFORE `commitSuccess`, the registry is never
      // touched, so retrying doesn't need to unwind a bad commit.
      //
      // This is the AUTONOMOUS, pre-commit guard: throwing is correct here
      // (retryable, nothing consumed yet). The sibling guard in
      // [[SessionSupport.register]] covers the interactive + rehydration paths,
      // where it LOGS-and-skips instead of throwing — dropping a finished
      // interactive turn or hard-aborting setup over one stale log field would
      // be worse than silently re-seeding on the next call.
      throw new OrcaFlowException(
        s"backend reported an invalid session id ('$wire') — refusing to record it for resume"
      )
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
