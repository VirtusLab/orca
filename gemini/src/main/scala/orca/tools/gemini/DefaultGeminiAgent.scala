package orca.tools.gemini

import orca.agents.{BackendTag, GeminiAgent, AgentConfig, Model, Prompts}
import orca.events.OrcaListener
import orca.backend.{Interaction, AgentBackend}
import orca.agents.BaseAgent

/** Default [[GeminiAgent]] implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseAgent]] and only adds the Gemini-specific
  * `flash` model accessor.
  */
private[orca] class DefaultGeminiAgent(
    backend: AgentBackend[BackendTag.Gemini.type],
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseAgent[BackendTag.Gemini.type, GeminiAgent](
      backend,
      config,
      prompts,
      events,
      interaction
    )
    with GeminiAgent:

  /** Pin the cheap-and-fast model variant. The literal id matches what's
    * available in the installed `gemini` CLI; newer versions may rename, in
    * which case callers override via `withConfig(AgentConfig(model =
    * Some(Model("..."))))`.
    */
  def flash: GeminiAgent = withModel(Model("gemini-2.5-flash"))

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name
  ): GeminiAgent =
    new DefaultGeminiAgent(
      backend,
      config,
      prompts,
      events,
      interaction,
      name
    )

private[orca] object DefaultGeminiAgent:

  /** The strong default model. Bare `gemini` pins this (in the runtime wiring,
    * mirroring claude's Opus default for the long-lived implementer); `flash`
    * opts down. Newer `gemini` CLI versions may rename the id — override via
    * `withConfig` if so.
    */
  val Pro: Model = Model("gemini-2.5-pro")
