package orca.tools.claude

import orca.llm.{BackendTag, ClaudeTool, LlmConfig, Model, Prompts}
import orca.events.{OrcaListener}

import orca.backend.{Interaction, LlmBackend}
import orca.llm.BaseLlmTool

/** Default ClaudeTool implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseLlmTool]] and only adds the
  * Claude-specific model accessors (`haiku` / `sonnet` / `opus`).
  *
  * Free-form text `autonomous.run` and structured `resultAs[O].autonomous.run`
  * go through the backend's headless mode. Interactive structured calls
  * (`resultAs[O].interactive.run`) spawn claude in stream-json mode and wrap
  * the subprocess in a `Conversation` that the supplied `interaction` drives to
  * completion.
  */
private[orca] class DefaultClaudeTool(
    backend: LlmBackend[BackendTag.ClaudeCode.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseLlmTool[BackendTag.ClaudeCode.type, ClaudeTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with ClaudeTool:

  def haiku: ClaudeTool = withModel(Model("claude-haiku-4-5"))
  def sonnet: ClaudeTool = withModel(Model("claude-sonnet-4-6"))
  def opus: ClaudeTool = withModel(Model("claude-opus-4-7"))

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
