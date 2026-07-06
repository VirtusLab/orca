package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  *
  * The `alreadyEmitted` flag tells the stage machinery whether an
  * `OrcaEvent.Error` has already been published for this failure: true when
  * thrown by `fail(...)` (which emits before throwing), false for direct throws
  * from tool code. A `stage` (and the top-level flow boundary) reads it to skip
  * a duplicate emit, and sets it to true once it does publish an Error — so an
  * enclosing stage, or the flow boundary, surfaces a tool-side failure exactly
  * once instead of re-reporting it at every level it unwinds through.
  */
class OrcaFlowException private[orca] (
    message: String,
    private[orca] var alreadyEmitted: Boolean
) extends RuntimeException(message):
  def this(message: String) = this(message, alreadyEmitted = false)

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
  * rate limit, a non-zero CLI exit, or a clean exit with no result).
  *
  * Distinct from a pre-spawn *open* failure (e.g. a transient broken pipe
  * before the session was registered), which stays a plain
  * [[OrcaFlowException]]. The distinction drives retry: the autonomous retry
  * loop reuses the same session id, which the backend locks once the turn has
  * run, so reopening it only yields "session already in use" / "broken pipe".
  * `AgentTurnFailed` is therefore NOT retried — it propagates immediately with
  * the real cause instead of that misleading cascade. Open failures and parse
  * failures remain retryable.
  *
  * Two sites, one contract: [[orca.backend.ForkedConversation.awaitResult]] is
  * the classifier (decides whether a failure becomes an `AgentTurnFailed`), and
  * `DefaultAgentCall.runAutonomousWithRetry` is the policy (the only place that
  * acts on the classification by refusing to retry it).
  */
class AgentTurnFailed(message: String) extends OrcaFlowException(message)
