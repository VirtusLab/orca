package orca.runner

import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  JsonData,
  Model,
  ToolSet
}

/** The shared `ClaudeAgent` skeleton for runner tests: every builder returns
  * `this`, every call throws. Concrete stubs override only what differs.
  */
private[runner] abstract class StubClaudeAgent(val name: String)
    extends ClaudeAgent:
  def haiku: ClaudeAgent = this
  def sonnet: ClaudeAgent = this
  def opus: ClaudeAgent = this
  def fable: ClaudeAgent = this
  def withModel(model: Model): ClaudeAgent = this
  def withNetworkTools(tools: Seq[String]): ClaudeAgent = this
  def withConfig(config: AgentConfig): ClaudeAgent = this
  def withSystemPrompt(prompt: String): ClaudeAgent = this
  def withName(name: String): ClaudeAgent = this
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    throw new UnsupportedOperationException
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    throw new UnsupportedOperationException
