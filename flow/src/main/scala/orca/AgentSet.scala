package orca

import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  Model,
  OpencodeAgent,
  PiAgent
}

/** The five per-backend agents a run wires. The runtime resolves the three role
  * agents (ADR 0020) from settings against this set before the [[FlowContext]]
  * exists; the per-role programmatic overrides on `flow(...)` (`planningAgent =
  * Some(_.claude.opus)`, …) are also selectors resolved against it.
  * [[FlowContext]] extends this trait, so inside a flow body the same accessors
  * resolve through the context.
  */
trait AgentSet:
  def claude: ClaudeAgent
  def codex: CodexAgent
  def opencode: OpencodeAgent
  def pi: PiAgent
  def gemini: GeminiAgent

  /** Resolve the per-backend agent named by `tag` — the single place a
    * [[BackendTag]] maps to one of the five agents, so a renamed or added case
    * is one match to update. `WiredAgents.byTag` and `RoleAgents` both resolve
    * through it.
    */
  private[orca] def agentFor(tag: BackendTag): Agent[?] = tag match
    case BackendTag.ClaudeCode => claude
    case BackendTag.Codex      => codex
    case BackendTag.Opencode   => opencode
    case BackendTag.Pi         => pi
    case BackendTag.Gemini     => gemini

  /** [[agentFor]] with an optional model pin (a settings `harness:model` value)
    * applied through `withModel`.
    */
  private[orca] def agentFor(
      tag: BackendTag,
      modelPin: Option[String]
  ): Agent[?] =
    val agent = agentFor(tag)
    modelPin.fold(agent)(m => agent.withModel(Model(m)))
