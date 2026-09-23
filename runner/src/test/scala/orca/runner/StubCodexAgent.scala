package orca.runner

import orca.agents.{
  AgentCall,
  AgentConfig,
  Announce,
  AutonomousTextCall,
  BackendTag,
  CodexAgent,
  JsonData,
  Model,
  ToolSet
}

/** The shared `CodexAgent` skeleton for runner tests: every builder returns
  * `this`, every call throws. Reports the real codex backend tag, so a role
  * resolved to it announces the `codex` harness as production does.
  */
private[runner] class StubCodexAgent extends CodexAgent:
  val name = "noop-codex"
  override private[orca] def backendTag: Option[BackendTag] =
    Some(BackendTag.Codex)
  def mini: CodexAgent = this
  def withModel(model: Model): CodexAgent = this
  def withConfig(config: AgentConfig): CodexAgent = this
  def withSystemPrompt(prompt: String): CodexAgent = this
  def withName(name: String): CodexAgent = this
  def withTools(tools: ToolSet): CodexAgent = this
  def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
    throw new UnsupportedOperationException
  def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
    throw new UnsupportedOperationException

/** Runs `onClose` when closed. */
private[runner] class ClosingCodex(onClose: () => Unit) extends StubCodexAgent:
  override private[orca] def close(): Unit = onClose()
