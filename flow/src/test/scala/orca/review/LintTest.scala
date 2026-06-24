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
  SessionId,
  ToolSet
}
import orca.events.{EventDispatcher}
import orca.{TestFlowContext}

class LintTest extends munit.FunSuite:

  // `lint` is now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  /** LlmTool that records the serialized prompt passed to
    * `resultAs.autonomous.run` and returns a canned ReviewResult. Method-scope
    * mutable var holds the captured string.
    */
  private class CapturingLlmTool(canned: ReviewResult)
      extends LlmTool[BackendTag.ClaudeCode.type]:
    var captured: String = ""
    // Contents of the `*.log` file the prompt references, read inside `run`
    // while it still exists — `lint` deletes it once `run` returns.
    var capturedFileContent: String = ""
    val name = "mock"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withTools(tools: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      new LlmCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
            def run[I](
                i: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                c: LlmConfig,
                emitPrompt: Boolean
            )(using
                a: AgentInput[I],
                _x: orca.InStage
            ): (SessionId[BackendTag.ClaudeCode.type], O) =
              captured = a.serialize(i)
              capturedFileContent = "`([^`]+\\.log)`".r
                .findFirstMatchIn(captured)
                .map(m => os.read(os.Path(m.group(1))))
                .getOrElse("")
              (
                SessionId[BackendTag.ClaudeCode.type]("lint-test"),
                canned.asInstanceOf[O]
              )
        def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???

  test("lint writes output to a file the prompt points to, returns its result"):
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
    val result = lint("echo LINT-BODY-MARKER", mock)
    assertEquals(result, expected)
    // The output goes to a file (so unbounded lint output can't overflow the
    // context); the prompt references that file and the exit status.
    assertEquals(mock.capturedFileContent.trim, "LINT-BODY-MARKER")
    assert(
      mock.captured.contains("exited with status 0"),
      s"prompt should include the exit status, got: ${mock.captured}"
    )
    // The temp file is removed once the summary returns.
    val filePath = "`([^`]+\\.log)`".r
      .findFirstMatchIn(mock.captured)
      .map(_.group(1))
      .getOrElse(
        fail(s"prompt should reference a .log file, got: ${mock.captured}")
      )
    assert(!os.exists(os.Path(filePath)), "lint should delete the temp file")

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
