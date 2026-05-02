package orca.review

import orca.{
  AgentInput,
  Announce,
  Backend,
  EventDispatcher,
  FlowContext,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  TestFlowContext,
  Title
}

class LintTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  /** LlmTool that records the serialized prompt passed to `resultAs.autonomous`
    * and returns a canned ReviewResult. Method-scope mutable var holds the
    * captured string.
    */
  private class CapturingLlmTool(canned: ReviewResult)
      extends LlmTool[Backend.ClaudeCode.type]:
    var captured: String = ""
    val name = "mock"
    def ask(p: String, c: LlmConfig = LlmConfig.default): String = ???
    def startSession(
        p: String,
        c: LlmConfig = LlmConfig.default
    ): (SessionId[Backend.ClaudeCode.type], String) = ???
    def continueSession(
        s: SessionId[Backend.ClaudeCode.type],
        p: String,
        c: LlmConfig = LlmConfig.default
    ): String = ???
    def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
      new LlmCall[Backend.ClaudeCode.type, O]:
        def autonomous[I](i: I, c: LlmConfig = LlmConfig.default)(using
            a: AgentInput[I]
        ): O =
          captured = a.serialize(i)
          canned.asInstanceOf[O]
        def startSession[I: AgentInput](
            i: I,
            c: LlmConfig = LlmConfig.default
        ): (SessionId[Backend.ClaudeCode.type], O) = ???
        def continueSession[I: AgentInput](
            sid: SessionId[Backend.ClaudeCode.type],
            i: I,
            c: LlmConfig = LlmConfig.default
        ): O = ???
        def interactive[I: AgentInput](
            i: I,
            c: LlmConfig = LlmConfig.default
        ): (SessionId[Backend.ClaudeCode.type], O) = ???
        def continueInteractive[I: AgentInput](
            sid: SessionId[Backend.ClaudeCode.type],
            i: I,
            c: LlmConfig = LlmConfig.default
        ): O = ???

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
