package orca.testkit

import orca.agents.{AgentConfig, Prompts, StructuredOutputMode}

/** Prompts that send a structured call's input unwrapped, so a test backend
  * sees exactly what the caller passed.
  */
object PassthroughPrompts extends Prompts:
  def autonomous(
      input: String,
      outputSchema: String,
      config: AgentConfig,
      mode: StructuredOutputMode
  ): String = input
  def interactive(
      input: String,
      outputSchema: String,
      config: AgentConfig
  ): String = input
  def retry(
      failedResponse: String,
      parseError: String,
      mode: StructuredOutputMode
  ): String = failedResponse
