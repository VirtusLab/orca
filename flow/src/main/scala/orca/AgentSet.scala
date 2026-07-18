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
  * agents (ADR 0020) from settings against this set BEFORE the [[FlowContext]]
  * exists, so each resolved role is a plain constructor fact of the context
  * rather than late-bound state; the per-role programmatic overrides on
  * `flow(...)` (`planningAgent = Some(_.claude.opus)`, …) are also selectors
  * resolved against it. [[FlowContext]] extends this trait, so inside a flow
  * body the same accessors resolve through the context.
  */
trait AgentSet:
  def claude: ClaudeAgent
  def codex: CodexAgent
  def opencode: OpencodeAgent
  def pi: PiAgent
  def gemini: GeminiAgent

  /** Resolve the per-backend agent named by `tag` — the single definition
    * session rehydration (`FlowLifecycle.targetAgent`) resolves a persisted
    * record's backend tag against, so a renamed or added [[BackendTag]] case is
    * one match to update, not one per call site. This default dispatches to the
    * five accessors above (only the matched one is touched, so it's used as-is
    * by every implementation — nothing to override). `private[orca]`: user code
    * never needs it, `claude`/`codex`/… cover every use it has.
    */
  private[orca] def agentFor(tag: BackendTag): Agent[?] = tag match
    case BackendTag.ClaudeCode => claude
    case BackendTag.Codex      => codex
    case BackendTag.Opencode   => opencode
    case BackendTag.Pi         => pi
    case BackendTag.Gemini     => gemini
