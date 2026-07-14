package orca.runner

import orca.agents.{
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  InteractiveAgentCall,
  JsonData,
  Model,
  SessionId,
  ToolSet
}

/** The discovery test seam: a `ClaudeAgent` whose `resultAs[O].autonomous.run`
  * returns `produce()` (cast to `O`) — tests exercising stack discovery hand
  * the lifecycle a canned [[StackDiscoveryResult]], or a thunk that throws to
  * drive the failure arm. Free-text calls throw like [[StubAgent]]'s (branch
  * naming falls back to the deterministic slug), so no test reaches a model.
  */
private[runner] class CannedDiscoveryAgent(produce: () => StackDiscoveryResult)
    extends ClaudeAgent:
  val name = "canned-discovery"
  def haiku = this
  def sonnet = this
  def opus = this
  def fable = this
  def withModel(model: Model) = this
  def withNetworkTools(t: Seq[String]) = this
  def withConfig(c: AgentConfig) = this
  def withSystemPrompt(p: String) = this
  def withName(n: String) = this
  def withTools(tools: ToolSet) = this
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    throw new UnsupportedOperationException
  def resultAs[O: JsonData: Announce]
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
