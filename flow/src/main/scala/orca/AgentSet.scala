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
    * is one match to update. Session rehydration
    * (`FlowLifecycle.rehydrateSessions`), `WiredAgents.byTag` and `RoleAgents`
    * all resolve through it.
    */
  private[orca] def agentFor(tag: BackendTag): Agent[?] = agentFor(tag, None)

  /** [[agentFor]] with an optional model pin (a settings `harness:model`
    * value), applied through the backend's own `withModel` — opencode's takes
    * the raw `provider/model` string, the rest a [[Model]].
    */
  private[orca] def agentFor(
      tag: BackendTag,
      modelPin: Option[String]
  ): Agent[?] = tag match
    case BackendTag.ClaudeCode =>
      modelPin.fold(claude)(m => claude.withModel(Model(m)))
    case BackendTag.Codex =>
      modelPin.fold(codex)(m => codex.withModel(Model(m)))
    case BackendTag.Opencode => modelPin.fold(opencode)(opencode.withModel)
    case BackendTag.Pi       => modelPin.fold(pi)(m => pi.withModel(Model(m)))
    case BackendTag.Gemini =>
      modelPin.fold(gemini)(m => gemini.withModel(Model(m)))
