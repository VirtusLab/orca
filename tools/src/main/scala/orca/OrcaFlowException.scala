package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)

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
