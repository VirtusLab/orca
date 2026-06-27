package orca.tools.codex

import orca.agents.{BackendTag, CodexAgent, AgentConfig, Model, Prompts}
import orca.events.{OrcaListener}

import orca.backend.{Interaction, AgentBackend}
import orca.agents.BaseAgent

/** Default [[CodexAgent]] implementation. Inherits the autonomous-text +
  * `resultAs[O]` plumbing from [[BaseAgent]] and only adds the Codex-specific
  * `mini` model accessor.
  */
private[orca] class DefaultCodexAgent(
    backend: AgentBackend[BackendTag.Codex.type],
    config: AgentConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main"
) extends BaseAgent[BackendTag.Codex.type, CodexAgent](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with CodexAgent:

  /** Pin the cheap-and-fast model variant. The literal model id matches what's
    * available in the installed `codex-cli` (gpt-5.4-mini in 0.125.0); newer
    * codex versions may rename, in which case callers override via
    * `withConfig(AgentConfig(model = Some(Model("..."))))`.
    */
  def mini: CodexAgent = withModel(Model("gpt-5.4-mini"))

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name
  ): CodexAgent =
    new DefaultCodexAgent(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )
