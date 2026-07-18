package orca

import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
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

  /** Resolve the per-backend agent named by `tag` — the single point session
    * rehydration (`FlowLifecycle.targetAgent`) resolves a persisted record's
    * backend tag against, so a renamed or added [[BackendTag]] case is one
    * match to update, not one per call site.
    */
  private[orca] def agentFor(tag: BackendTag): Agent[?] = tag match
    case BackendTag.ClaudeCode => claude
    case BackendTag.Codex      => codex
    case BackendTag.Opencode   => opencode
    case BackendTag.Pi         => pi
    case BackendTag.Gemini     => gemini
