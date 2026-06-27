package orca.runner

import orca.agents.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  JsonData,
  AgentCall,
  AgentConfig,
  ToolSet
}

/** A `ClaudeAgent` stub for tests that must pass the now-mandatory leading
  * model to `flow(...)` (ADR 0018 §2.5) but assert wiring/lifecycle, not LLM
  * behaviour. Every call throws — no test reaches one.
  */
object StubAgent:
  val claude: ClaudeAgent = new ClaudeAgent:
    val name = "stub"
    def haiku = this
    def sonnet = this
    def opus = this
    def fable = this
    def withNetworkTools(t: Seq[String]) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      throw new UnsupportedOperationException
