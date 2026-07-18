package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue (unless a stage catches it and recovers).
  *
  * Error reporting is orthogonal to this type: the runtime reports each failure
  * exactly once by tracking already-reported throwables in a context-owned
  * identity set (`FlowContext.markErrorReported` / `errorAlreadyReported`), for
  * plain `RuntimeException`s just as for this type.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)

/** Returned in the `Left` of [[orca.backend.Conversation.awaitResult]] when the
  * user cancels the current interactive call, and rethrown by
  * [[orca.backend.Interaction.drive]] so the enclosing `stage(...)` can catch
  * it and decide whether to fail the stage or recover.
  *
  * Deliberately a subtype of [[OrcaFlowException]]: when nobody catches it, the
  * right default is to end the flow like any other failure, and a blanket
  * `OrcaFlowException` recovery treats a cancelled conversation as "this
  * attempt produced no result". The autonomous path — the only one with a retry
  * policy — cannot produce this (see
  * [[orca.backend.Conversations.drainAutonomous]]), so subtyping never causes a
  * cancellation to be retried.
  */
class OrcaInteractiveCancelled(
    message: String = "interactive session cancelled"
) extends OrcaFlowException(message)

/** A semantic failure of an agent *turn that actually ran*: the conversation
  * was spawned — so the backend has already registered the session id — and
  * then ended in a terminal error (`is_error`, a rate limit, a non-zero CLI
  * exit, or a clean exit with no result). Distinct from a pre-spawn *open*
  * failure (e.g. a transient broken pipe before the session was registered),
  * which stays a plain [[OrcaFlowException]].
  *
  * Marks a failure as non-retryable: reusing the locked session id makes a
  * retry futile. Classified at [[orca.backend.ForkedConversation.awaitResult]];
  * `DefaultAgentCall.runAutonomousWithRetry` is the policy that acts on it.
  *
  * `cause` is optional so `getCause` still reaches the original exception
  * (stack trace, exact type) for `--verbose`/debug inspection rather than being
  * flattened into the message string.
  */
class AgentTurnFailed(message: String, cause: Throwable | Null = null)
    extends OrcaFlowException(message):
  if cause != null then initCause(cause): Unit
