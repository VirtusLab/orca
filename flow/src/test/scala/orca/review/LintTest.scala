package orca.review

import orca.{FlowContext}
import orca.plan.Title
import orca.llm.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveLlmCall,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId
}
import orca.events.{EventDispatcher}
import orca.{TestFlowContext}

class LintTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  /** LlmTool that records the serialized prompt passed to
    * `resultAs.autonomous.run` and returns a canned ReviewResult. Method-scope
    * mutable var holds the captured string.
    */
  private class CapturingLlmTool(canned: ReviewResult)
      extends LlmTool[BackendTag.ClaudeCode.type]:
    var captured: String = ""
    val name = "mock"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withReadOnly: LlmTool[BackendTag.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      new LlmCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
            def run[I](i: I, c: LlmConfig = LlmConfig.default)(using
                a: AgentInput[I]
            ): O =
              captured = a.serialize(i)
              canned.asInstanceOf[O]
            def startSession[I: AgentInput](
                i: I,
                c: LlmConfig = LlmConfig.default
            ): (SessionId[BackendTag.ClaudeCode.type], O) = ???
            def continueSession[I: AgentInput](
                sid: SessionId[BackendTag.ClaudeCode.type],
                i: I,
                c: LlmConfig = LlmConfig.default
            ): O = ???
        def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???

  test("lint runs the command, passes output to the LLM, returns its result"):
    given FlowContext = ctx
    val expected = ReviewResult(
      issues = List(
        ReviewIssue(
          Severity.Warning,
          0.8,
          Title("Unused import"),
          "unused import",
          None,
          None,
          None
        )
      )
    )
    val mock = new CapturingLlmTool(expected)
    val result = lint("echo 'unused import in Foo.scala'", mock)
    assertEquals(result, expected)
    assert(
      mock.captured.contains("unused import in Foo.scala"),
      s"expected prompt to include the lint output, got: ${mock.captured}"
    )

  test("lint short-circuits to ReviewResult.empty when the command is silent"):
    given FlowContext = ctx
    val mock = new CapturingLlmTool(ReviewResult.empty)
    val result = lint("true", mock)
    assertEquals(result, ReviewResult.empty)
    assertEquals(
      mock.captured,
      "",
      "LLM should not be called when there's no lint output"
    )
