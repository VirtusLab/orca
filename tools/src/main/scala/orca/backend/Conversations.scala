package orca.backend

import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{AutoApprove, BackendTag, SessionId, StructuredOutputMode}
import orca.sweep.{EnvCookie, EnvCookieSweep}

import ox.{Ox, supervised}

/** Drains a [[Conversation]] for the autonomous path, mapping conversation
  * events to [[OrcaEvent]]s and returning the awaited `AgentResult`.
  *
  * A structured call withholds its closing assistant turn — the caller emits
  * the result via `OrcaEvent.StructuredResult` instead. Where the payload
  * arrives as reply text (`StructuredOutputMode.RawText`) that turn IS the
  * JSON; where it arrives as a tool call (claude's `--json-schema` exit call,
  * `StructuredOutputMode.Tool`) it is the model's sign-off restating what the
  * `StructuredResult` states in full. Both `Tool`-mode drivers suppress the
  * exit call itself (`ClaudeConversation.handleAssistantTurn`,
  * `OpencodeConversation.isStructuredOutputEcho`), so no turn-opening event
  * follows the sign-off to release it. Mid-turn narration is unaffected — a
  * real tool call still releases the turn that announced it. The withheld-turn
  * state machine lives in [[TurnBuffer]].
  *
  * Interactive-only events that reach this drain are handled explicitly to
  * avoid blocking the subprocess: `ApproveTool` is auto-denied and
  * `UserQuestion` auto-answered, both also surfacing as `OrcaEvent.Error`.
  */
private[orca] object Conversations:

  /** Whether the drain renders a conversation's closing assistant prose turn,
    * or keeps it out of the prose stream because it carries the structured
    * payload.
    */
  private enum ClosingProse:
    case Withhold, Render

  /** A structured call withholds its closing prose turn; a call with no schema
    * renders it. The wire's [[StructuredOutputMode]] doesn't come into it on
    * either path: in `RawText` the closing turn IS the payload, in `Tool` it is
    * the sign-off restating the payload the caller emits as `StructuredResult`
    * (see the object scaladoc), and `Prompts.interactive` asks every backend
    * for a JSON-only final message regardless.
    */
  private def closingProse(conv: Conversation[?]): ClosingProse =
    if conv.outputSchema.isDefined then ClosingProse.Withhold
    else ClosingProse.Render

  /** Renders each completed assistant turn's prose as one `emit`, holding back
    * the CLOSING turn when it carries the structured payload.
    *
    * A turn can only be recognised as closing after the fact, so a completed
    * turn is parked in `withheld` and released by [[onActivity]] — the first
    * assistant event of the next turn — which keeps a turn's narration ahead of
    * the tool calls it announces. What survives to end-of-stream is the closing
    * turn, and only that is dropped.
    *
    * State is confined to the drain's single-threaded event loop — the mutable
    * `StringBuilder`/`var` here is a deliberate, reviewed deviation from the
    * codebase's Ox-concurrency default, not an actor oversight: this runs on
    * one thread only, so no channel/actor would buy anything.
    */
  private final class TurnBuffer(closing: ClosingProse, emit: String => Unit):
    private val current = new StringBuilder
    private var withheld: Option[String] = None

    /** Assistant activity opening a turn (`ConversationEvent.opensTurn`): the
      * parked turn isn't the closing one after all, so release it. Call before
      * rendering the triggering event, so a turn's narration lands ahead of the
      * tool call it announces.
      */
    def onActivity(): Unit =
      withheld.foreach(emit)
      withheld = None

    def append(delta: String): Unit =
      val _ = current.append(delta)

    def turnEnd(): Unit =
      if current.nonEmpty then
        val text = current.toString
        current.clear()
        closing match
          case ClosingProse.Withhold => withheld = Some(text)
          case ClosingProse.Render   => emit(text)

    /** Normal end of stream: the parked turn IS the payload — drop it (the
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

    /** The drain threw while reading the stream: the turn boundary that would
      * have told the payload from narration never arrived, so flush everything
      * rather than drop prose. Worst case the user sees a JSON blob once.
      *
      * Only a throw from the iterator lands here. A failure the wire reports
      * (an `is_error` result) closes the stream normally, so [[finishNormally]]
      * runs and the parked turn is dropped like any other closing turn — an
      * unfinished one still flushes from `current`.
      */
    def finishAbnormally(): Unit =
      withheld.foreach(emit)
      withheld = None
      flushCurrent()

    private def flushCurrent(): Unit =
      if current.nonEmpty then
        emit(current.toString)
        current.clear()

  /** Why the tool wasn't already approved. The auto-approve set is blamed only
    * when the tool really is outside it: a backend that ignores orca's set
    * (opencode, whose own server config answers `permission.asked`) can ask
    * about a tool the set already lists.
    */
  private def denialCause(toolName: String, autoApprove: AutoApprove): String =
    autoApprove match
      case AutoApprove.Only(tools) if !tools.contains(toolName) =>
        "it is not in the auto-approve set"
      case _ => "the backend asked for approval itself"

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      autoApprove: AutoApprove,
      events: OrcaListener = OrcaListener.noop
  )(using Ox): AgentResult[B] =
    val buffer = new TurnBuffer(
      closingProse(conv),
      text => events.onEvent(OrcaEvent.AssistantMessage(text))
    )
    try
      conv.events.foreach: event =>
        if event.opensTurn then buffer.onActivity()
        event match
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
            val cause = denialCause(toolName, autoApprove)
            respond(
              ApprovalDecision.Deny(
                Some(
                  s"$toolName denied: $cause, and autonomous mode cannot prompt"
                )
              )
            )
            events.onEvent(
              OrcaEvent.Error(
                s"Denied $toolName: $cause; autonomous mode cannot prompt"
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
      autoApprove: AutoApprove,
      events: OrcaListener = OrcaListener.noop
  )(using Ox): AgentResult[B] =
    val result = drainAutonomous(conv, autoApprove, events)
    sessions.commitAfterDrain(session, result.wireId)
    result

  /** The complete autonomous-turn shell shared by all backends: open the
    * conversation inside its own supervised scope, drain + commit, and always
    * cancel before the scope joins (load-bearing on failure paths —
    * `drainAndCommit` does not tear down). `open` runs inside the scope so the
    * conversation's forks bind to it.
    *
    * The cancel reaches only what is still linked to the agent process; the
    * sweep catches what detached.
    */
  def runAutonomous[B <: BackendTag](
      session: SessionId[B],
      sessions: SessionSupport[B],
      autoApprove: AutoApprove,
      events: OrcaListener
  )(open: Ox ?=> Conversation[B]): AgentResult[B] =
    supervised:
      val conv = open
      try drainAndCommit(conv, session, sessions, autoApprove, events)
      finally
        conv.cancel()
        EnvCookieSweep.afterTurn(conv.envCookie, events)

  /** Interactive counterpart to the autonomous drain's `TurnBuffer`: wraps a
    * live [[Conversation]] so its assistant PROSE (text deltas + the turn
    * boundary that closes them) is translated into `OrcaEvent.AssistantMessage`
    * on `events` — exactly like [[drainAutonomous]] — instead of being exposed
    * on the conversation's own event stream. A structured call has its closing
    * turn withheld the same way `TurnBuffer` does (see [[closingProse]] for why
    * the backend's wire doesn't come into it); the caller re-surfaces the
    * payload via `OrcaEvent.StructuredResult` instead (see
    * `AgentCall.runInteractiveOnce`).
    *
    * Every OTHER event (tool calls/results, approvals, questions, errors, the
    * user's own messages) passes through to the returned conversation's
    * `events` unchanged and un-delayed — this only touches prose, so an
    * `ApproveTool`/`UserQuestion` prompt is never stuck waiting behind a
    * withheld turn (which would deadlock: the subprocess blocks on the
    * response, and nothing but a later event would ever release it).
    */
  def withholdInteractiveProse[B <: BackendTag](
      conv: Conversation[B],
      listener: OrcaListener
  ): Conversation[B] =
    new Conversation[B]:
      def outputSchema: Option[String] = conv.outputSchema
      override def structuredOutputMode: StructuredOutputMode =
        conv.structuredOutputMode
      def canAskUser: Boolean = conv.canAskUser
      def cancel(): Unit = conv.cancel()
      override def envCookie: Option[EnvCookie] = conv.envCookie
      def awaitResult()(using Ox) = conv.awaitResult()
      def events(using Ox): Iterator[ConversationEvent] =
        ProseWithholdingIterator(
          conv.events,
          closingProse(conv),
          text => listener.onEvent(OrcaEvent.AssistantMessage(text))
        )

  /** Filters an interactive [[ConversationEvent]] stream through a
    * [[TurnBuffer]]: prose events (`AssistantTextDelta` /
    * `AssistantThinkingDelta` / `AssistantTurnEnd`) are consumed and converted
    * into `emit` calls instead of being yielded; every other event is yielded
    * immediately, un-reordered relative to other non-prose events.
    *
    * Termination is treated as `TurnBuffer.finishNormally` regardless of why
    * `inner` ran out (clean end or cancellation) — this iterator has no way to
    * tell the two apart, and the common case (a completed structured turn) is
    * what matters: don't echo a turn the wire itself closed. An exception from
    * `inner` is treated as `finishAbnormally` (flush rather than risk losing
    * genuine prose) and rethrown.
    *
    * Like [[TurnBuffer]], the mutable `buffer`/`pending` state is single-thread
    * confined (one iterator, one consumer) — a deliberate, reviewed deviation
    * from the Ox-concurrency default, not an actor oversight.
    */
  private final class ProseWithholdingIterator(
      inner: Iterator[ConversationEvent],
      closing: ClosingProse,
      emit: String => Unit
  ) extends Iterator[ConversationEvent]:
    private val buffer = new TurnBuffer(closing, emit)
    private val pending =
      scala.collection.mutable.Queue.empty[ConversationEvent]
    private var settled = false

    private def fill(): Unit =
      try
        while pending.isEmpty && inner.hasNext do
          val event = inner.next()
          if event.opensTurn then buffer.onActivity()
          event match
            case ConversationEvent.AssistantTextDelta(text) =>
              buffer.append(text)
            case ConversationEvent.AssistantThinkingDelta(_) => ()
            case ConversationEvent.AssistantTurnEnd          => buffer.turnEnd()
            case other => pending.enqueue(other)
        if pending.isEmpty && !settled && !inner.hasNext then
          settled = true
          buffer.finishNormally()
      catch
        case t: Throwable =>
          if !settled then
            settled = true
            buffer.finishAbnormally()
          throw t

    def hasNext: Boolean =
      fill()
      pending.nonEmpty

    def next(): ConversationEvent =
      fill()
      pending.dequeue()
