package orca.tools.opencode

import orca.backend.{Interaction, LlmBackend}
import orca.events.OrcaListener
import orca.llm.{
  BackendTag,
  BaseLlmTool,
  LlmConfig,
  Model,
  OpencodeTool,
  Prompts
}

/** Default [[OpencodeTool]]. Inherits the autonomous-text + `resultAs[O]`
  * plumbing from [[BaseLlmTool]] and adds OpenCode's provider-prefixed model
  * accessors. The pinned ids are convenience defaults — any id from `opencode
  * models` is valid; bump them as the catalog moves.
  */
private[orca] class DefaultOpencodeTool(
    backend: LlmBackend[BackendTag.Opencode.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseLlmTool[BackendTag.Opencode.type, OpencodeTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with OpencodeTool:

  def anthropicOpus: OpencodeTool = withModel("anthropic", "claude-opus-4-8")
  def anthropicSonnet: OpencodeTool =
    withModel("anthropic", "claude-sonnet-4-6")
  def anthropicHaiku: OpencodeTool = withModel("anthropic", "claude-haiku-4-5")
  def openaiGpt5: OpencodeTool = withModel("openai", "gpt-5.4")
  def openaiGpt5Codex: OpencodeTool = withModel("openai", "gpt-5.3-codex")
  def openaiGpt5Mini: OpencodeTool = withModel("openai", "gpt-5-mini")

  // Two-arg form validates and joins via OpencodeModel (one place); the
  // accessors above share it. `withModel(String)` takes an already-joined id.
  override def withModel(provider: String, modelId: String): OpencodeTool =
    super[BaseLlmTool].withModel(OpencodeModel(provider, modelId))

  // `super` disambiguates from BaseLlmTool's protected `withModel(Model)`.
  def withModel(providerModel: String): OpencodeTool =
    super[BaseLlmTool].withModel(Model(providerModel))

  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): OpencodeTool =
    new DefaultOpencodeTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )
