package orca.backend

import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{BackendTag, SessionId}

import ox.{Ox, supervised}

/** Drains a [[Conversation]] for the autonomous path, mapping conversation
  * events to [[OrcaEvent]]s and returning the awaited `AgentResult`.
  *
  * Structured mode (`conv.outputSchema.isDefined`) withholds the last assistant
  * turn so the closing JSON payload doesn't surface as an `AssistantMessage` —
  * the caller emits it via `OrcaEvent.StructuredResult` instead. The
  * withheld-turn state machine lives in [[TurnBuffer]].
  *
  * Interactive-only events that reach this drain are handled explicitly to
  * avoid blocking the subprocess: `ApproveTool` is auto-denied and
  * `UserQuestion` auto-answered, both also surfacing as `OrcaEvent.Error`.
  */
private[orca] object Conversations:

  /** Structured-mode turn buffer: streams every completed turn EXCEPT the most
    * recent, which is withheld one turn (the final turn is the JSON payload,
    * the caller re-surfaces it via OrcaEvent.StructuredResult). Turn N thus
    * renders when turn N+1 completes — a one-turn display delay that keeps the
    * payload out of the prose stream. In non-structured mode every turn flushes
    * immediately.
    *
    * State is confined to the drain's single-threaded event loop.
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
      * caller emits StructuredResult); flush any unfinished current buffer.
      *
      * Since ForkedConversation auto-closes every completed turn, a normal
      * session ends its last turn with an `AssistantTurnEnd` that already ran
      * `turnEnd()`, leaving `current` empty. `flushCurrent()` is a safety net
      * for a turn the stream left open (abnormal termination mid-turn).
      */
    def finishNormally(): Unit =
      withheld = None
      flushCurrent()

    /** Abnormal end (the drain threw mid-stream): nothing is reliably the
      * payload, so flush everything rather than drop prose. Worst case the user
      * sees a JSON blob once.
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
  )(using Ox): AgentResult[B] =
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
          // The subprocess blocks on stdin waiting for our decision and
          // autonomous mode has no user to ask, so deny with a reason (so the
          // agent can adapt) and surface as an error; dropping would deadlock.
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
          // The ask_user MCP bridge isn't wired in autonomous mode (see
          // `ConversationMode.Autonomous`), so this should be unreachable. If it
          // ever fires, the bridge thread is blocked on `respond` — unblock it
          // rather than leak the thread.
          respond("[autonomous mode: no user available to answer]")
          events.onEvent(
            OrcaEvent.Error(
              "ask_user fired during an autonomous call; auto-answered"
            )
          )
        case ConversationEvent.UserMessage(_) =>
          // Wire-level echo of input we already sent; surfaced upstream as
          // `OrcaEvent.UserPrompt` from the Agent layer.
          ()
        case ConversationEvent.ToolResult(_, _, _) =>
          // Tool output volume is unbounded (full cargo-test logs, etc.), so it
          // isn't surfaced here; the matching `AssistantToolCall` already went
          // out as `OrcaEvent.ToolUse`. Listeners needing raw output subscribe
          // at the `ConversationEvent` layer instead.
          ()
      buffer.finishNormally()
    catch
      case t: Throwable =>
        buffer.finishAbnormally()
        throw t
    conv.awaitResult() match
      case Right(result) => result
      // Autonomous callers can't produce a Left; throw to honour the
      // AgentResult call shape.
      case Left(cancelled) => throw cancelled

  /** Shared autonomous-turn finalize for the subprocess backends: drain the
    * conversation, then — on success only — commit the session as resumable.
    * Returns the drained result verbatim.
    *
    * The commit runs only after a clean drain, so a subprocess that crashed
    * before registering its session doesn't wedge the registry into resuming a
    * session that was never created. Drain failures propagate verbatim; the
    * retryability classification already happened in
    * [[ForkedConversation.awaitResult]].
    */
  def drainAndCommit[B <: BackendTag](
      conv: Conversation[B],
      session: SessionId[B],
      sessions: SessionSupport[B],
      events: OrcaListener = OrcaListener.noop
  )(using Ox): AgentResult[B] =
    val result = drainAutonomous(conv, events)
    sessions.commitAfterDrain(session, result.wireId)
    result

  /** The complete autonomous-turn shell shared by all backends: open the
    * conversation inside its own supervised scope, drain + commit, and always
    * cancel before the scope joins (load-bearing on failure paths —
    * `drainAndCommit` does not tear down). `open` runs inside the scope so the
    * conversation's forks bind to it.
    */
  def runAutonomous[B <: BackendTag](
      session: SessionId[B],
      sessions: SessionSupport[B],
      events: OrcaListener
  )(open: Ox ?=> Conversation[B]): AgentResult[B] =
    supervised:
      val conv = open
      try drainAndCommit(conv, session, sessions, events)
      finally conv.cancel()
