package orca.runner

import orca.agents.{Agent, BackendTag, ClaudeAgent}
import orca.testkit.{ScriptedBackend, TestAgent}

/** The discovery test seam: an agent whose structured turns answer `produce()`
  * wrapped in the [[StackDiscoveryReply]] envelope — tests exercising stack
  * discovery hand the lifecycle a canned [[StackDiscoveryResult]], or a thunk
  * that throws to drive the failure arm. Free-text turns fail (branch naming
  * falls back to the deterministic slug), so no test reaches a model.
  */
private[runner] object CannedDiscoveryAgent:
  def apply(produce: => StackDiscoveryResult): ClaudeAgent =
    on(BackendTag.ClaudeCode)(produce)

  def on[B <: BackendTag & Singleton](tag: B)(
      produce: => StackDiscoveryResult
  ): Agent[B] =
    TestAgent(
      ScriptedBackend.replying(tag): turn =>
        if turn.outputSchema.isEmpty then
          throw new UnsupportedOperationException("free-text turn")
        ScriptedBackend.json(StackDiscoveryReply(produce))
      ,
      "canned-discovery"
    )
