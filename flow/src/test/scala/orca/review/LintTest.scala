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
    val result = lint(List("echo LINT-BODY-MARKER"), mock)
    assertEquals(result, expected)
    // Small output is inlined directly — the sandbox-safe path (a read-only
    // autonomous agent that can't reach files outside its worktree still sees
    // it). So no `.txt` file is referenced, and the labeled block — the
    // command with its exit status, then the trimmed output — is in the
    // prompt.
    assert(
      mock.captured.contains(
        "$ echo LINT-BODY-MARKER   (exit 0)\nLINT-BODY-MARKER"
      ),
      s"prompt should inline the labeled lint output, got: ${mock.captured}"
    )
    assert(
      !"`[^`]+\\.txt`".r.findFirstIn(mock.captured).isDefined,
      s"small output must not spill to a file, got: ${mock.captured}"
    )

  test("lint labels each command's block, blank line between blocks"):
    given FlowContext = ctx
    val mock = new CapturingAgent(expected)
    val result = lint(List("echo ONE-OUT", "echo TWO-OUT; exit 2"), mock)
    assertEquals(result, expected)
    // Both commands run (a failing first linter doesn't hide the second's
    // diagnostics) and the summariser sees one concatenation, in command
    // order, each block labeled with its own command and exit status.
    assert(
      mock.captured.contains(
        "$ echo ONE-OUT   (exit 0)\nONE-OUT\n\n" +
          "$ echo TWO-OUT; exit 2   (exit 2)\nTWO-OUT"
      ),
      s"prompt should hold both labeled blocks, got: ${mock.captured}"
    )

  test("lint reaches the summariser when a silent command exits nonzero"):
    given FlowContext = ctx
    val mock = new CapturingAgent(expected)
    val result = lint(List("exit 3"), mock)
    assertEquals(result, expected)
    // A linter can fail with no stdout — the nonzero exit must not be
    // swallowed by the empty-output short-circuit. The block is the label
    // line alone, telling the summariser "ran, empty output, exit 3".
    assert(
      mock.captured.contains("```\n$ exit 3   (exit 3)\n```"),
      s"prompt should hold the label-only block, got: ${mock.captured}"
    )

  test(
    "lint spills large output to a file under workDir/.orca/cache, removed after"
  ):
    // An explicit workDir different from os.pwd pins that the spill follows
    // the flow's working tree, not the JVM's current directory.
    val workDir = orca.testkit.TempDirs.dir()
    assertNotEquals(workDir, os.pwd)
    given FlowContext =
      new TestFlowContext(new EventDispatcher(Nil), workDir = workDir)
    val mock = new CapturingAgent(expected)
    // Output well over the inline threshold, carrying a marker so we can check
    // the file the agent is pointed at actually holds the command's output.
    val big = "printf 'LINT-BIG-MARKER'; printf 'X%.0s' {1..9000}"
    val result = lint(List(big), mock)
    assertEquals(result, expected)
    // The spilled file holds the same labeled text the inline path would
    // embed, so the summariser sees per-command exit statuses either way.
    assert(
      mock.capturedFileContent.startsWith(
        s"$$ $big   (exit 0)\nLINT-BIG-MARKER"
      ),
      "the referenced file should hold the labeled lint output"
    )
    val filePath = "`([^`]+\\.txt)`".r
      .findFirstMatchIn(mock.captured)
      .map(_.group(1))
      .getOrElse(
        fail(s"prompt should reference a .txt file, got: ${mock.captured}")
      )
    // The file lives inside the flow's working tree (under the self-ignoring
    // .orca/cache/), not /tmp, so a sandboxed reviewer can read it; and it's
    // removed once the summary returns.
    assert(
      os.Path(filePath).startsWith(workDir / ".orca" / "cache"),
      s"large-output file should live under <workDir>/.orca/cache/, got: $filePath"
    )
    assert(!os.exists(os.Path(filePath)), "lint should delete the temp file")

  test("lint short-circuits when every command is silent and exits zero"):
    given FlowContext = ctx
    val mock = new CapturingAgent(ReviewResult.empty)
    val result = lint(List("true", "true"), mock)
    assertEquals(result, ReviewResult.empty)
    assertEquals(
      mock.captured,
      "",
      "LLM should not be called when no command produced output or failed"
    )

  test("lint with no commands is a no-op: empty result, no LLM call"):
    given FlowContext = ctx
    val mock = new CapturingAgent(ReviewResult.empty)
    val result = lint(Nil, mock)
    assertEquals(result, ReviewResult.empty)
    assertEquals(
      mock.captured,
      "",
      "LLM should not be called when the command list is empty"
    )
