package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  *
  * The `alreadyEmitted` flag tells the stage machinery whether an
  * `OrcaEvent.Error` has already been published for this failure (true when
  * thrown by `fail(...)`, which emits before throwing; false for direct throws
  * from tool code). Stage-level catch sites use this to avoid double-emit while
  * still surfacing tool-side failures that would otherwise be silent.
  */
class OrcaFlowException private[orca] (
    message: String,
    private[orca] val alreadyEmitted: Boolean
) extends RuntimeException(message):
  def this(message: String) = this(message, alreadyEmitted = false)

/** Returned in the `Left` of [[Conversation.awaitResult]] when the user cancels
  * the current interactive call, and rethrown by [[Interaction.drive]] so the
  * enclosing `stage(...)` can catch it and decide whether to fail the stage or
  * recover. Cancellation is a local signal, not a flow-level abort. Direct
  * callers of `Conversation` pattern-match on the Either; `drive`-using callers
  * see the exception-shaped propagation that the stage machinery expects.
  */
class OrcaInteractiveCancelled(
    message: String = "interactive session cancelled"
) extends OrcaFlowException(message)
