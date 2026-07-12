package orca.tools.opencode

import orca.backend.{Interaction, AgentBackend}
import orca.events.OrcaListener
import orca.agents.{
  BackendTag,
  BaseAgent,
  AgentConfig,
  Model,
  OpencodeAgent,
  Prompts
}

/** Default [[OpencodeAgent]]. Inherits the autonomous-text + `resultAs[O]`
  * plumbing from [[BaseAgent]] and adds OpenCode's provider-prefixed model
  * accessors. The pinned ids are convenience defaults — any id from `opencode
  * models` is valid; bump them as the catalog moves.
  */
private[orca] class DefaultOpencodeAgent(
    backend: AgentBackend[BackendTag.Opencode.type],
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main",
    override val role: Option[String] = None
) extends BaseAgent[BackendTag.Opencode.type, OpencodeAgent](
      backend,
      config,
      prompts,
      events,
      interaction
    )
    with OpencodeAgent:

  def anthropicOpus: OpencodeAgent = withModel("anthropic", "claude-opus-4-8")
  def anthropicSonnet: OpencodeAgent =
    withModel("anthropic", "claude-sonnet-5")
  def anthropicHaiku: OpencodeAgent = withModel("anthropic", "claude-haiku-4-5")
  def openaiSol: OpencodeAgent = withModel("openai", "gpt-5.6-sol")
  def openaiTerra: OpencodeAgent = withModel("openai", "gpt-5.6-terra")
  def openaiLuna: OpencodeAgent = withModel("openai", "gpt-5.6-luna")

  // Cheap is provider-matched so incidental work doesn't pull in a second
  // provider's auth: an openai-led tool's cheap is an openai model, otherwise
  // anthropic haiku (also the default when no model is pinned). Reads the
  // provider prefix directly (not OpencodeModel.split, which throws on a bare
  // id) so resolving the cheap model can never break a flow.
  override protected def defaultCheap: OpencodeAgent =
    config.model.map(m => Model.name(m).takeWhile(_ != '/')) match
      case Some("openai") => openaiLuna
      case _              => anthropicHaiku

  // Two-arg form validates and joins via OpencodeModel (one place); the
  // accessors above share it. `withModel(String)` takes an already-joined id.
  override def withModel(provider: String, modelId: String): OpencodeAgent =
    super[BaseAgent].withModel(OpencodeModel(provider, modelId))

  // `super` disambiguates from BaseAgent's protected `withModel(Model)`.
  def withModel(providerModel: String): OpencodeAgent =
    super[BaseAgent].withModel(Model(providerModel))

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name,
      role: Option[String] = role
  ): OpencodeAgent =
    new DefaultOpencodeAgent(
      backend,
      config,
      prompts,
      events,
      interaction,
      name,
      role
    )
