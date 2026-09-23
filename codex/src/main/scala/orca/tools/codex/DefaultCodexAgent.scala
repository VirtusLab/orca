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
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "main",
    override val role: Option[String] = None
) extends BaseAgent[BackendTag.Codex.type, CodexAgent](
      backend,
      config,
      prompts,
      events,
      interaction
    )
    with CodexAgent:

  /** Pin the cheap-and-fast model variant. Older codex versions may not offer
    * it, in which case callers pin the right id with
    * `codex.withModel(Model("..."))`.
    */
  def mini: CodexAgent = withModel(Model("gpt-6-luna"))

  protected def copyTool(
      config: AgentConfig = config,
      name: String = name,
      role: Option[String] = role
  ): CodexAgent =
    new DefaultCodexAgent(
      backend,
      config,
      prompts,
      events,
      interaction,
      name,
      role
    )

private[orca] object DefaultCodexAgent:

  /** The strong default model that bare `codex` pins; `mini` opts down.
    *
    * Pinning is what makes a default codex turn priceable at all: `codex exec
    * --json` names no model anywhere on its stream (probed 2026-08-08,
    * codex-cli 0.145.0 — `thread.started` carries `thread_id` alone, with and
    * without `-m`), so an unpinned turn lands under `(unknown)`. This id is
    * OpenAI's recommended Codex model; codex-cli 0.155.1 offers it (0.145.0
    * lacks it); older CLIs need an upgrade or `codex.withModel(...)`.
    */
  val Sol: Model = Model("gpt-6-sol")
