package orca.tools.opencode

import orca.agents.Model

/** The provider-qualified models orca pins by name. Convenience defaults — any
  * id from `opencode models` is valid.
  */
private[orca] object OpencodeModels:
  val AnthropicOpus: Model = OpencodeModel("anthropic", "claude-opus-5-5")
  val AnthropicSonnet: Model = OpencodeModel("anthropic", "claude-sonnet-5")
  val AnthropicHaiku: Model = OpencodeModel("anthropic", "claude-haiku-4-5")
  val OpenaiAstra: Model = OpencodeModel("openai", "gpt-6-astra")
  val OpenaiSol: Model = OpencodeModel("openai", "gpt-6-sol")
  val OpenaiLuna: Model = OpencodeModel("openai", "gpt-6-luna")
