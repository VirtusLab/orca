package orca.runner

import orca.{FlowContext, StackSettings}
import orca.review.ReviewerCatalog
import orca.tools.{FsTool, GitHubTool, RuntimeGit}
import orca.agents.Agent
import orca.events.{OrcaEvent, OrcaListener}

/** Production FlowContext wiring. Constructed by `runFlow` AFTER the three role
  * agents are resolved and lifecycle setup has run, so the role agents and
  * `stackSettings` are plain constructor facts. Does not own the agents:
  * `runFlow` closes them when the run ends.
  */
private[orca] class DefaultFlowContext(
    val userPrompt: String,
    val workDir: os.Path,
    dispatcher: OrcaListener,
    // The three role agents (ADR 0020), resolved by `runFlow`.
    val planningAgent: Agent[?],
    val codingAgent: Agent[?],
    val reviewAgent: Agent[?],
    wired: WiredAgents,
    private[orca] val runtimeGit: RuntimeGit,
    val gh: GitHubTool,
    val fs: FsTool,
    /** Resolved stack settings (ADR 0019): `FlowLifecycle.setup` resolves them
      * before the context is constructed, so they arrive frozen — the body (and
      * the loops it calls) sees one immutable value.
      */
    val stackSettings: StackSettings,
    /** The reviewer definitions this run works from (shipped set + discovered
      * tiers): resolved by `runFlow` before the context exists, so it arrives
      * frozen like `stackSettings`.
      */
    val reviewerCatalog: ReviewerCatalog
) extends FlowContext:

  export wired.{claude, codex, opencode, pi, gemini}

  def emit(event: OrcaEvent): Unit = dispatcher.onEvent(event)
