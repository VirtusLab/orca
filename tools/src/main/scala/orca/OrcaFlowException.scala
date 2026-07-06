package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  *
  * Exactly-once error reporting no longer rides on this exception: the runtime
  * tracks which throwables have already surfaced an `OrcaEvent.Error` in a
  * context-owned identity set (`FlowContext.markErrorReported` /
  * `errorAlreadyReported`), so a failure is reported once no matter how many
  * stages it unwinds through — and plain `RuntimeException`s are covered too,
  * not just this type.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)

/** Returned in the `Left` of [[orca.backend.Conversation.awaitResult]] when the
  * user cancels the current interactive call, and rethrown by
  * [[orca.backend.Interaction.drive]] so the enclosing `stage(...)` can catch
  * it and decide whether to fail the stage or recover. Cancellation is a local
  * signal, not a flow-level abort. Direct callers of `Conversation`
  * pattern-match on the Either; `drive`-using callers see the exception-shaped
  * propagation that the stage machinery expects.
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
  */
class AgentTurnFailed(message: String) extends OrcaFlowException(message)
