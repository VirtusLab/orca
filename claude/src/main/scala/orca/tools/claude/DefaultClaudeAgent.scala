package orca.tools.claude

import orca.agents.{BackendTag, ClaudeAgent, AgentConfig, Model, Prompts}
import orca.events.{OrcaListener}

import orca.backend.Interaction
import orca.agents.BaseAgent

/** Default ClaudeAgent implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseAgent]] and only adds the Claude-specific
  * model accessors (`haiku` / `sonnet` / `opus`).
  *
  * Free-form text `autonomous.run` and structured `resultAs[O].autonomous.run`
  * go through the backend's headless mode. Interactive structured calls
  * (`resultAs[O].interactive.run`) spawn claude in stream-json mode and wrap
  * the subprocess in a `Conversation` that the supplied `interaction` drives to
  * completion.
  */
private[orca] class DefaultClaudeAgent(
    backend: ClaudeBackend,
    config: AgentConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseAgent[BackendTag.ClaudeCode.type, ClaudeAgent](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with ClaudeAgent:

  def haiku: ClaudeAgent = withModel(Model("claude-haiku-4-5"))
  def sonnet: ClaudeAgent = withModel(Model("claude-sonnet-4-6"))
  def opus: ClaudeAgent = withModel(DefaultClaudeAgent.Opus1M)
  def fable: ClaudeAgent = withModel(DefaultClaudeAgent.Fable)

  /** Per the trait: configure the read-only network allowlist by swapping in a
    * reconfigured backend (the allowlist is claude-specific, so it lives there
    * rather than in the shared `AgentConfig`). Constructs directly rather than
    * via [[copyTool]] because the latter threads the current backend unchanged.
    */
  def withNetworkTools(tools: Seq[String]): ClaudeAgent =
    new DefaultClaudeAgent(
      backend.withNetworkTools(tools),
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name
  ): ClaudeAgent =
    new DefaultClaudeAgent(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )

private[orca] object DefaultClaudeAgent:
  /** The default coding model: Opus with the 1M-token context window, selected
    * via the documented `[1m]` model-alias suffix (Claude Code model-config; no
    * beta header needed). The main implementer session is long-lived and
    * accumulates context across tasks, so 1M is what keeps it from overflowing
    * ("Prompt is too long"). Both bare `claude` (see `DefaultFlowContext`) and
    * `claude.opus` resolve to this; cheaper one-shot / auxiliary calls go
    * through `claude.sonnet` / `claude.haiku`.
    */
  val Opus1M: Model = Model("claude-opus-4-8[1m]")

  /** Fable: the most capable tier, above Opus. Opt in via `claude.fable` for
    * the hardest one-shots; the long-lived implementer stays on Opus (the
    * default — see [[Opus1M]]) for cost. 1M context at standard pricing.
    */
  val Fable: Model = Model("claude-fable-5")
