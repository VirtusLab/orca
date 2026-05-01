package orca

/** The channel that connects a flow to its user. `listeners` are registered on
  * the event dispatcher so the channel can show stage progress, streamed
  * output, token totals, and errors. `drive` runs a live interactive session:
  * it consumes [[ConversationEvent]]s in order, renders them, and routes user
  * input + tool-approval decisions back via the [[Conversation]] handle.
  * `TerminalInteraction` is the default; a Slack or HTTP implementation can be
  * substituted by passing `interaction = ...` to `flow(...)`.
  */
trait Interaction:
  def listeners: List[OrcaListener]

  /** Drive a live interactive session to completion. Returns the final
    * [[LlmResult]] on success, throws [[OrcaInteractiveCancelled]] if the user
    * cancelled mid-session, or any [[OrcaFlowException]] subtype for other
    * failures.
    */
  def drive[B <: Backend](conversation: Conversation[B]): LlmResult[B]
