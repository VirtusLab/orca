package orca.backend

import orca.OrcaInteractiveCancelled
import orca.llm.BackendTag

/** Drains a [[Conversation]] for the autonomous path: walks every
  * [[ConversationEvent]] off the iterator (the read loop only terminates once
  * the subprocess finishes), then returns the result `awaitResult()` produces.
  *
  * The autonomous path doesn't render events; it consumes them so the
  * subprocess can keep producing without back-pressure, and so that any
  * `OrcaEvent`s the future wiring emits (per-tool-use lines, agent-message
  * summaries) fire in arrival order. Today the drain is silent — task 4 of the
  * unification plan will start emitting `OrcaEvent`s from here.
  *
  * `awaitResult()`'s `Left(OrcaInteractiveCancelled)` becomes a thrown
  * `OrcaInteractiveCancelled` so autonomous callers — which never expose a
  * cancel button — don't have to special-case a value they could never have
  * produced. Genuine backend failures (non-zero exit, missing turn-completed,
  * etc.) already surface as thrown [[orca.OrcaFlowException]]s from inside the
  * conversation's reader loop.
  */
private[orca] object Conversations:

  def drainAutonomous[B <: BackendTag](conv: Conversation[B]): LlmResult[B] =
    conv.events.foreach(_ => ())
    conv.awaitResult() match
      case Right(result)   => result
      case Left(cancelled) =>
        // Autonomous callers can't cancel — a `Left` here would have to come
        // from a peer thread that has its hands on the conversation, which
        // is not how autonomous calls are wired. Surface as a throw so the
        // call shape (returns `LlmResult`) is honoured.
        throw cancelled
