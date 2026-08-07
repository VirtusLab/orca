package orca.pr

import orca.{FlowContext, TestFlowContext}
import orca.agents.{
  Agent,
  AgentCall,
  AgentConfig,
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  SessionId,
  ToolSet
}
import orca.events.EventDispatcher

class SummarisePrTest extends munit.FunSuite:

  // `summarisePr` is gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private def nyi(m: String): Nothing =
    throw new NotImplementedError(s"$m unused by summarisePr")

  /** Records the prompt and returns a fixed summary. */
  private class CapturingSummariser extends Agent[BackendTag.ClaudeCode.type]:
    var captured: String = ""
    val name: String = "summariser"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      nyi("autonomous")
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      new AgentCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
            private[orca] def runWithSession[I](
                input: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                sessionName: Option[String],
                config: Option[AgentConfig],
                emitPrompt: Boolean
            )(using a: AgentInput[I], _x: orca.InStage): O =
              captured = a.serialize(input)
              PrSummary("title", "body").asInstanceOf[O]
        def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
          nyi("interactive")

  test("the diff keeps a line whose first non-blank character is `|`"):
    given FlowContext = new TestFlowContext(new EventDispatcher(Nil))
    val agent = new CapturingSummariser()
    val _ = summarisePr(agent, diff = "+first line\n |context with pipe")
    assert(
      agent.captured.contains("\n |context with pipe"),
      s"the `|` line must reach the summariser intact, got: ${agent.captured}"
    )
