package orca.runner

import orca.agents.{
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  SessionId
}

/** The discovery test seam: a `ClaudeAgent` whose `resultAs[O].autonomous.run`
  * returns `produce()` (cast to `O`) — tests exercising stack discovery hand
  * the lifecycle a canned [[StackDiscoveryResult]], or a thunk that throws to
  * drive the failure arm. Free-text calls throw like [[StubAgent]]'s (branch
  * naming falls back to the deterministic slug), so no test reaches a model.
  */
private[runner] class CannedDiscoveryAgent(produce: () => StackDiscoveryResult)
    extends StubClaudeAgent("canned-discovery"):
  override def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): O =
            produce().asInstanceOf[O]
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        throw new UnsupportedOperationException

private[runner] object CannedDiscoveryAgent:
  /** The common fixed-result case. */
  def apply(result: StackDiscoveryResult): CannedDiscoveryAgent =
    new CannedDiscoveryAgent(() => result)
