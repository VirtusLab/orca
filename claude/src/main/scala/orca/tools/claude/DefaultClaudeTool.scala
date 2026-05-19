package orca.tools.claude

import orca.events.{OrcaListener}
import orca.{BackendTag, ClaudeTool, LlmConfig, Prompts}
import orca.backend.{Interaction, LlmBackend}
import orca.io.AbstractDefaultLlmTool

/** Default ClaudeTool implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[AbstractDefaultLlmTool]] and only adds the
  * Claude-specific model accessors (`haiku` / `sonnet` / `opus`).
  *
  * Free-form text calls (`autonomous.run` / `startSession` / `continueSession`)
  * and structured `resultAs[O].autonomous.*` go through the backend's headless
  * mode. Interactive structured calls (`resultAs[O].interactive.startSession`)
  * spawn claude in stream-json mode and wrap the subprocess in a `Conversation`
  * that the supplied `interaction` drives to completion.
  */
class DefaultClaudeTool(
    backend: LlmBackend[BackendTag.ClaudeCode.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends AbstractDefaultLlmTool[BackendTag.ClaudeCode.type, ClaudeTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with ClaudeTool:

  def haiku: ClaudeTool = withModel("claude-haiku-4-5")
  def sonnet: ClaudeTool = withModel("claude-sonnet-4-6")
  def opus: ClaudeTool = withModel("claude-opus-4-7")

  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): ClaudeTool =
    new DefaultClaudeTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )
