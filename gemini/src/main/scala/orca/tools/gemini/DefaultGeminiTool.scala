package orca.tools.gemini

import orca.llm.{BackendTag, GeminiTool, LlmConfig, Model, Prompts}
import orca.events.OrcaListener
import orca.backend.{Interaction, LlmBackend}
import orca.llm.BaseLlmTool

/** Default [[GeminiTool]] implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseLlmTool]] and only adds the
  * Gemini-specific `flash` model accessor.
  */
private[orca] class DefaultGeminiTool(
    backend: LlmBackend[BackendTag.Gemini.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseLlmTool[BackendTag.Gemini.type, GeminiTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with GeminiTool:

  /** Pin the cheap-and-fast model variant. The literal id matches what's
    * available in the installed `gemini` CLI; newer versions may rename, in
    * which case callers override via `withConfig(LlmConfig(model =
    * Some(Model("..."))))`.
    */
  def flash: GeminiTool = withModel(Model("gemini-2.5-flash"))

  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): GeminiTool =
    new DefaultGeminiTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )

private[orca] object DefaultGeminiTool:

  /** The strong default model. Bare `gemini` pins this (in the runtime wiring,
    * mirroring claude's Opus default for the long-lived implementer); `flash`
    * opts down. Newer `gemini` CLI versions may rename the id — override via
    * `withConfig` if so.
    */
  val Pro: Model = Model("gemini-2.5-pro")
