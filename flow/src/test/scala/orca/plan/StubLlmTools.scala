package orca.plan

import orca.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  Backend,
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
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "stub"
  def autonomous: AutonomousTextCall[Backend.ClaudeCode.type] = ???
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def withName(n: String): LlmTool[Backend.ClaudeCode.type] = this

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    new LlmCall[Backend.ClaudeCode.type, O]:
      val autonomous: AutonomousLlmCall[Backend.ClaudeCode.type, O] =
        new AutonomousLlmCall[Backend.ClaudeCode.type, O]:
          def run[I: AgentInput](
              input: I,
              config: LlmConfig = LlmConfig.default
          ): O = plan.asInstanceOf[O]
          def startSession[I: AgentInput](
              input: I,
              config: LlmConfig = LlmConfig.default
          ): (SessionId[Backend.ClaudeCode.type], O) = ???
          def continueSession[I: AgentInput](
              sid: SessionId[Backend.ClaudeCode.type],
              input: I,
              config: LlmConfig = LlmConfig.default
          ): O = ???
      def interactive: InteractiveLlmCall[Backend.ClaudeCode.type, O] = ???

/** Test double that throws on every method — used to assert that a code path
  * doesn't call the LLM.
  */
private[plan] class ExplodingLlm(reason: String)
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "exploding"
  def autonomous: AutonomousTextCall[Backend.ClaudeCode.type] =
    throw new AssertionError(reason)
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def withName(n: String): LlmTool[Backend.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    throw new AssertionError(reason)
