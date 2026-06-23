package orca.runner

import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeTool,
  JsonData,
  LlmCall,
  LlmConfig,
  ToolSet
}

/** A `ClaudeTool` stub for tests that must pass the now-mandatory leading model
  * to `flow(...)` (ADR 0018 §2.5) but assert wiring/lifecycle, not LLM
  * behaviour. Every call throws — no test reaches one.
  */
object StubLlm:
  val claude: ClaudeTool = new ClaudeTool:
    val name = "stub"
    def haiku = this
    def sonnet = this
    def opus = this
    def fable = this
    def withNetworkTools(t: Seq[String]) = this
    def withConfig(c: LlmConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      throw new UnsupportedOperationException
