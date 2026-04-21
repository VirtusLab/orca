package orca

trait PromptTemplate:
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String
  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String
