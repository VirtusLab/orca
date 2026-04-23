package orca

import ox.Ox

case class LlmResult[B <: Backend](
    sessionId: SessionId[B],
    output: String,
    usage: Usage
)

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

  /** Launch an interactive session and return a live [[Conversation]] the
    * caller hands to an [[Interaction.drive]] for rendering and user
    * steering. The backend owns the subprocess and NDJSON parsing; the
    * channel owns UX.
    *
    * `outputSchema` is the JSON Schema the agent's final reply must
    * conform to, or `None` for free-form text. Backends that support
    * structured-output validation (claude's `--json-schema`) enforce
    * it; those that don't can ignore the parameter and let the caller
    * validate post-hoc.
    */
  def runInteractive(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]

  def continueInteractive(
      sessionId: SessionId[B],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[B]
