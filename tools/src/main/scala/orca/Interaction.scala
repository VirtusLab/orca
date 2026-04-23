package orca

/** The channel that connects a flow to its user. `listeners` are registered on
  * the event dispatcher so the channel can show stage progress, streamed
  * output, token totals, and errors; `runInteractive` is invoked when a stage
  * hands control to the agent and blocks until the agent releases it.
  * `TerminalInteraction` is the default; a Slack or HTTP implementation can be
  * substituted by passing `interaction = ...` to `orca(...)`.
  */
trait Interaction:
  def listeners: List[OrcaListener]
  def runInteractive(handle: InteractiveHandle[?]): Unit
