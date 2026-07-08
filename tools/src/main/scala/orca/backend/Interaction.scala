package orca.backend

import orca.agents.{BackendTag}
import orca.events.{OrcaListener}

import ox.Ox

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
    * [[AgentResult]] on success, throws [[OrcaInteractiveCancelled]] if the
    * user cancelled mid-session, or any [[OrcaFlowException]] subtype for other
    * failures.
    *
    * Takes `using Ox`: consuming the conversation ([[Conversation.events]] /
    * [[Conversation.awaitResult]]) starts the driver's workers in the caller's
    * per-turn scope. `AgentCall.runInteractiveOnce` supplies that scope.
    */
  def drive[B <: BackendTag](conversation: Conversation[B])(using
      Ox
  ): AgentResult[B]

  /** Release any background resources (worker threads, channels, etc.). The
    * runtime calls this once after the flow body completes, regardless of
    * success or failure. Default is a no-op; implementations that spawn a
    * renderer thread or hold an open connection should override.
    */
  def close(): Unit = ()
