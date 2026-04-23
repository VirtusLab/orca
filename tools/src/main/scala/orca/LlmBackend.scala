package orca

import ox.Ox

case class LlmResult[B <: Backend](
    sessionId: SessionId[B],
    output: String,
    usage: Usage
)

trait InteractiveHandle[B <: Backend]:
  def awaitTermination(): LlmResult[B]

trait LlmBackend[B <: Backend]:
  def prepareWorkspace(
      config: LlmConfig,
      outputSchema: String,
      workDir: os.Path
  )(using Ox): Unit
  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[B]
  def continueHeadless(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[B]
  def runInteractive(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[B]
  def continueInteractive(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[B]
