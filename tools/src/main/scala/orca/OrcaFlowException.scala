package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue (unless a stage catches it and recovers).
  *
  * Error reporting is orthogonal to this type: the runtime reports each failure
  * exactly once by tracking already-reported throwables in a context-owned
  * identity set (`FlowContext.markErrorReported` / `errorAlreadyReported`), so
  * a failure surfaces a single `OrcaEvent.Error` no matter how many stages it
  * unwinds through — for plain `RuntimeException`s just as for this type.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)

/** Returned in the `Left` of [[orca.backend.Conversation.awaitResult]] when the
  * user cancels the current interactive call, and rethrown by
  * [[orca.backend.Interaction.drive]] so the enclosing `stage(...)` can catch
  * it and decide whether to fail the stage or recover.
  *
  * Deliberately a subtype of [[OrcaFlowException]] even though cancellation is
  * designed to be caught at the stage driving the conversation: when nobody
  * catches it, the right default is for the cancellation to end the flow like
  * any other flow failure (failure teardown, non-zero exit) — the user said
  * stop and no stage volunteered a recovery. It also makes a blanket
  * `OrcaFlowException` recovery treat a cancelled conversation as "this attempt
  * produced no result", which is the reading such a handler wants. Callers
  * needing cancellation-specific behaviour catch this subtype; direct callers
  * of `Conversation` pattern-match on the Either instead. The autonomous path —
  * the only one with a retry policy — cannot produce this (see
  * [[orca.backend.Conversations.drainAutonomous]]), so subtyping never causes a
  * cancellation to be retried.
  */
class OrcaInteractiveCancelled(
    message: String = "interactive session cancelled"
) extends OrcaFlowException(message)

/** A semantic failure of an agent *turn that actually ran*: the conversation
  * was spawned — so the backend has already registered the session id — and
  * then ended in a terminal error (`is_error` such as "Prompt is too long", a
  * rate limit, a non-zero CLI exit, or a clean exit with no result). Distinct
  * from a pre-spawn *open* failure (e.g. a transient broken pipe before the
  * session was registered), which stays a plain [[OrcaFlowException]].
  *
  * Role: this tag marks a failure as non-retryable. Why reusing the locked
  * session id makes a retry futile is owned at the decision point,
  * [[orca.backend.ForkedConversation.awaitResult]] (the classifier);
  * `DefaultAgentCall.runAutonomousWithRetry` is the policy that acts on it.
  *
  * `cause` is optional (both wrap sites — `awaitResult`'s generic-failure arm
  * and `runAutonomousWithRetry`'s re-attribution rewrap — pass the throwable
  * they're wrapping) so `getCause` still reaches the original exception (stack
  * trace, exact type) for `--verbose`/debug inspection instead of being
  * flattened into the message string and discarded.
  */
class AgentTurnFailed(message: String, cause: Throwable | Null = null)
    extends OrcaFlowException(message):
  if cause != null then initCause(cause): Unit
