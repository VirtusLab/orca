package orca.tools.claude

import orca.agents.{BackendTag, ClaudeAgent, AgentConfig, Model, Prompts}
import orca.events.{OrcaListener}

import orca.backend.Interaction
import orca.agents.BaseAgent

/** Default ClaudeAgent implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseAgent]] and only adds the Claude-specific
  * model accessors (`haiku` / `sonnet` / `opus` / `fable`).
  */
private[orca] class DefaultClaudeAgent(
    backend: ClaudeBackend,
    config: AgentConfig,
    prompts: Prompts,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main",
    override val role: Option[String] = None
) extends BaseAgent[BackendTag.ClaudeCode.type, ClaudeAgent](
      backend,
      config,
      prompts,
      events,
      interaction
    )
    with ClaudeAgent:

  def haiku: ClaudeAgent = withModel(Model("claude-haiku-4-5"))
  def sonnet: ClaudeAgent = withModel(Model("claude-sonnet-5"))
  def opus: ClaudeAgent = withModel(DefaultClaudeAgent.Opus1M)
  def fable: ClaudeAgent = withModel(DefaultClaudeAgent.Fable)

  /** Configure the read-only network allowlist by swapping in a reconfigured
    * backend (the allowlist is claude-specific, so it lives there, not in
    * shared `AgentConfig`). Constructs directly rather than via [[copyTool]],
    * which threads the current backend unchanged.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeAgent =
    new DefaultClaudeAgent(
      backend.withNetworkTools(tools),
      config,
      prompts,
      events,
      interaction,
      name,
      role
    )

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name,
      role: Option[String] = role
  ): ClaudeAgent =
    new DefaultClaudeAgent(
      backend,
      config,
      prompts,
      events,
      interaction,
      name,
      role
    )

private[orca] object DefaultClaudeAgent:
  /** The default coding model: Opus with the 1M-token context window, via the
    * `[1m]` model-alias suffix. The main implementer session is long-lived and
    * accumulates context across tasks, so 1M keeps it from overflowing ("Prompt
    * is too long"). Cheaper one-shot calls go through `claude.sonnet` /
    * `claude.haiku`.
    */
  val Opus1M: Model = Model("claude-opus-4-8[1m]")

  /** Fable: the most capable tier, above Opus. Opt in via `claude.fable` for
    * the hardest one-shots; the long-lived implementer stays on Opus (the
    * default) for cost. 1M context at standard pricing.
    */
  val Fable: Model = Model("claude-fable-5")
