package orca.review

import orca.{FlowContext}
import orca.plan.Title
import orca.agents.{
  AgentInput,
  Announce,
  AutonomousAgentCall,
  AutonomousTextCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
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

  /** Agent that records the serialized prompt passed to
    * `resultAs.autonomous.run` and returns a canned ReviewResult. Method-scope
    * mutable var holds the captured string.
    */
  private class CapturingAgent(canned: ReviewResult)
      extends Agent[BackendTag.ClaudeCode.type]:
    var captured: String = ""
    // Contents of the `*.txt` file the prompt references, if any, read inside
    // `run` while it still exists — `lint` deletes it once `run` returns. Empty
    // when the output was inlined (small) rather than spilled to a file.
    var capturedFileContent: String = ""
    val name = "mock"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
    def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
    def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
    def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      new AgentCall[BackendTag.ClaudeCode.type, O]:
        val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
          new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
            private[orca] def runWithSession[I](
                i: I,
                session: SessionId[BackendTag.ClaudeCode.type],
                c: Option[AgentConfig],
                emitPrompt: Boolean
            )(using
                a: AgentInput[I],
                _x: orca.InStage
            ): O =
              captured = a.serialize(i)
              capturedFileContent = "`([^`]+\\.txt)`".r
                .findFirstMatchIn(captured)
                .map(m => os.read(os.Path(m.group(1))))
                .getOrElse("")
              canned.asInstanceOf[O]
        def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
          ???

  private val expected = ReviewResult(
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

  test("lint inlines small output into the prompt, references no file"):
    given FlowContext = ctx
    val mock = new CapturingAgent(expected)
    val result = lint("echo LINT-BODY-MARKER", mock)
    assertEquals(result, expected)
    // Small output is inlined directly — the sandbox-safe path (a read-only
    // autonomous agent that can't reach files outside its worktree still sees
    // it). So no `.txt` file is referenced, and the marker is in the prompt.
    assert(
      mock.captured.contains("LINT-BODY-MARKER"),
      s"prompt should inline the lint output, got: ${mock.captured}"
    )
    assert(
      !"`[^`]+\\.txt`".r.findFirstIn(mock.captured).isDefined,
      s"small output must not spill to a file, got: ${mock.captured}"
    )
    assert(
      mock.captured.contains("exited with status 0"),
      s"prompt should include the exit status, got: ${mock.captured}"
    )

  test("lint spills large output to a file under .orca, removed after"):
    given FlowContext = ctx
    val mock = new CapturingAgent(expected)
    // Output well over the inline threshold, carrying a marker so we can check
    // the file the agent is pointed at actually holds the command's output.
    val big = "printf 'LINT-BIG-MARKER'; printf 'X%.0s' {1..9000}"
    val result = lint(big, mock)
    assertEquals(result, expected)
    assert(
      mock.capturedFileContent.contains("LINT-BIG-MARKER"),
      "the referenced file should hold the lint output"
    )
    val filePath = "`([^`]+\\.txt)`".r
      .findFirstMatchIn(mock.captured)
      .map(_.group(1))
      .getOrElse(
        fail(s"prompt should reference a .txt file, got: ${mock.captured}")
      )
    // The file lives inside the working tree (under .orca/), not /tmp, so a
    // sandboxed reviewer can read it; and it's removed once the summary returns.
    assert(
      os.Path(filePath).segments.contains(".orca"),
      s"large-output file should live under .orca/, got: $filePath"
    )
    assert(!os.exists(os.Path(filePath)), "lint should delete the temp file")

  test("lint short-circuits to ReviewResult.empty when the command is silent"):
    given FlowContext = ctx
    val mock = new CapturingAgent(ReviewResult.empty)
    val result = lint("true", mock)
    assertEquals(result, ReviewResult.empty)
    assertEquals(
      mock.captured,
      "",
      "LLM should not be called when there's no lint output"
    )
