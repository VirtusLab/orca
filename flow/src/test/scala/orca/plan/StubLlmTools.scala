package orca.plan

import orca.{
  Announce,
  Backend,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId
}

/** Test double that returns a canned string from `ask`. The other
  * methods aren't exercised by the extended-plan tests; they throw
  * to make accidental use loud.
  */
private[plan] class CannedLlm(reply: String)
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "stub"
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String = reply
  def startSession(p: String, c: LlmConfig = LlmConfig.default) = ???
  def continueSession(s: SessionId[Backend.ClaudeCode.type], p: String, c: LlmConfig = LlmConfig.default) = ???
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def resultAs[O: JsonData : Announce]: LlmCall[Backend.ClaudeCode.type, O] = ???

/** Test double that throws on every method — used to assert that a
  * code path doesn't call the LLM.
  */
private[plan] class ExplodingLlm(reason: String)
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "exploding"
  def ask(p: String, c: LlmConfig = LlmConfig.default): String =
    throw new AssertionError(reason)
  def startSession(p: String, c: LlmConfig = LlmConfig.default) =
    throw new AssertionError(reason)
  def continueSession(s: SessionId[Backend.ClaudeCode.type], p: String, c: LlmConfig = LlmConfig.default) =
    throw new AssertionError(reason)
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def resultAs[O: JsonData : Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    throw new AssertionError(reason)
