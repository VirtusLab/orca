package orca.plan

import orca.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveLlmCall,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId
}

/** Test double whose `resultAs[Plan].autonomous.run` returns a pre-built
  * `Plan`. Other call shapes throw so accidental use surfaces immediately.
  */
private[plan] class CannedPlanLlm(plan: Plan)
    extends LlmTool[BackendTag.ClaudeCode.type]:
  val name: String = "stub"
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this

  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    new LlmCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
          def run[I: AgentInput](
              input: I,
              config: LlmConfig = LlmConfig.default
          ): O = plan.asInstanceOf[O]
          def startSession[I: AgentInput](
              input: I,
              config: LlmConfig = LlmConfig.default
          ): (SessionId[BackendTag.ClaudeCode.type], O) = ???
          def continueSession[I: AgentInput](
              sid: SessionId[BackendTag.ClaudeCode.type],
              input: I,
              config: LlmConfig = LlmConfig.default
          ): O = ???
      def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???

/** Test double that throws on every method — used to assert that a code path
  * doesn't call the LLM.
  */
private[plan] class ExplodingLlm(reason: String)
    extends LlmTool[BackendTag.ClaudeCode.type]:
  val name: String = "exploding"
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    throw new AssertionError(reason)
  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    throw new AssertionError(reason)
