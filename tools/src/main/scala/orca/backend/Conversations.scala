package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag

/** Drains a [[Conversation]] for the autonomous path: walks every
  * [[ConversationEvent]] off the iterator (the read loop only terminates once
  * the subprocess finishes), emits a matching [[OrcaEvent]] to the listener for
  * the user-visible ones, then returns the awaited `LlmResult`.
  *
  * Buffered text flushes at every `AssistantTurnEnd` (as `OrcaEvent.AssistantMessage`).
  * Structured mode (`conv.outputSchema.isDefined`) withholds the most recent
  * turn — if another turn follows it was intermediate prose (flush); if the
  * stream ends with it withheld it was the JSON payload (drop, the caller
  * surfaces it via `OrcaEvent.StructuredResult`). End-of-stream flushes any
  * unfinished buffer outside structured mode so a mid-turn crash doesn't lose
  * partial output.
  *
  * Other mappings: `AssistantToolCall(name, raw)` → `OrcaEvent.ToolUse(name,
  * raw)` (raw JSON passes through; the terminal listener summarises);
  * `AssistantThinkingDelta` dropped; `ConversationEvent.Error` re-emits;
  * `ToolResult`/`UserMessage`/`ApproveTool`/`UserQuestion` swallowed (not
  * relevant to the autonomous log).
  *
  * `awaitResult()`'s `Left(OrcaInteractiveCancelled)` becomes a thrown
  * `OrcaInteractiveCancelled` so autonomous callers — which never expose a
  * cancel button — don't have to special-case a value they could never have
  * produced. Genuine backend failures already surface as thrown
  * [[orca.OrcaFlowException]]s from inside the conversation's reader loop.
  */
private[orca] object Conversations:

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B] =
    val structuredMode = conv.outputSchema.isDefined
    val textBuf = new StringBuilder
    // The previously-closed turn's text, kept around in structured mode so
    // we can decide what to do with it once we know whether another turn
    // follows. Always `None` outside structured mode.
    var withheld: Option[String] = None
    def closeTurn(): Unit =
      if textBuf.nonEmpty then
        val text = textBuf.toString
        textBuf.clear()
        if structuredMode then
          // Previous hold was intermediate — flush it. The new text might
          // turn out to be the final structured payload, so hold it.
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
        // Tool results, user-message echoes, approval / user-question
        // prompts: not relevant to the autonomous log. Approval and
        // ask_user shouldn't ever reach an autonomous drain (no MCP, all
        // tools pre-approved) — if they do, drop rather than crash so
        // the result still flows.
        case _ => ()
    finally
      // The `try`-body may have thrown mid-turn (InterruptedException from
      // a cancelled scope, listener bug, etc.); finish flushing what we
      // can. Non-structured: emit any withheld text. Structured: drop the
      // withheld text — it's the final JSON payload (clean end) or a
      // half-formed payload (crash; the thrown exception is the diagnostic).
      closeTurn()
      if !structuredMode then
        withheld.foreach(p => events.onEvent(OrcaEvent.AssistantMessage(p)))
    conv.awaitResult() match
      case Right(result)   => result
      case Left(cancelled) =>
        // Autonomous callers can't cancel — a `Left` here would have to come
        // from a peer thread that has its hands on the conversation, which
        // is not how autonomous calls are wired. Surface as a throw so the
        // call shape (returns `LlmResult`) is honoured.
        throw cancelled
