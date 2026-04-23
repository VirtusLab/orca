package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** Fake LlmCall whose `prompt` returns a scripted list of outputs and whose
  * `continueSession` returns scripted IgnoredIssues — both cast through Any
  * because the trait is generic over output type.
  */
class FakeLlmCall[O](
    autonomousOutputs: Iterator[Any],
    continueSessionOutputs: Iterator[Any]
) extends LlmCall[Backend.ClaudeCode.type, O]:
  def autonomous[I](input: I, config: LlmConfig = LlmConfig.default)(using
      AgentInput[I]
  ): O = autonomousOutputs.next().asInstanceOf[O]
  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???
  def continueSession[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = continueSessionOutputs.next().asInstanceOf[O]
  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???
  def continueInteractive[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = ???

class FakeLlmTool(
    override val name: String,
    promptOutputs: List[Any] = Nil,
    continueSessionOutputs: List[Any] = Nil
) extends LlmTool[Backend.ClaudeCode.type]:
  private val promptIt = promptOutputs.iterator
  private val continueIt = continueSessionOutputs.iterator

  def result[O: Schema: ConfiguredJsonValueCodec]
      : LlmCall[Backend.ClaudeCode.type, O] =
    new FakeLlmCall[O](promptIt, continueIt)

  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String = ""
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this

class ReviewAndFixTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(desc: String, confidence: Double = 1.0): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = confidence,
      description = desc,
      file = None,
      line = None,
      suggestion = None
    )

  test("returns empty IgnoredIssues when no reviewer reports issues"):
    given FlowContext = ctx
    val silentReviewer = new FakeLlmTool(
      name = "quiet",
      promptOutputs = List(ReviewResult.empty)
    )
    val coder = new FakeLlmTool("coder")
    val result = reviewAndFix(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(silentReviewer),
      task = "do the thing"
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("filters issues below the confidence threshold"):
    given FlowContext = ctx
    // Reviewer reports two issues: one above and one below threshold.
    // After filtering, only the high-confidence one reaches the coder, which
    // ignores it. Re-evaluation produces the same two issues (filtered to one),
    // which is already ignored, so the loop terminates.
    val noisyIssue = issue("flaky", confidence = 0.3)
    val realIssue = issue("real bug", confidence = 0.95)
    val reviewer = new FakeLlmTool(
      name = "loud",
      promptOutputs = List.fill(10)(
        ReviewResult(List(noisyIssue, realIssue), "two")
      )
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs =
        List(IgnoredIssues(List(IgnoredIssue(realIssue, "accepted"))))
    )
    val result = reviewAndFix(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "build the widget",
      confidenceThreshold = 0.7
    )
    assertEquals(result.issues.map(_.issue), List(realIssue))
    assertEquals(result.issues.map(_.reason), List("accepted"))

  test("runs multiple reviewers and merges their issues"):
    given FlowContext = ctx
    val issueA = issue("A")
    val issueB = issue("B")
    val reviewerA = new FakeLlmTool(
      name = "a",
      promptOutputs = List.fill(10)(ReviewResult(List(issueA), "a"))
    )
    val reviewerB = new FakeLlmTool(
      name = "b",
      promptOutputs = List.fill(10)(ReviewResult(List(issueB), "b"))
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs = List(
        IgnoredIssues(
          List(
            IgnoredIssue(issueA, "ok-a"),
            IgnoredIssue(issueB, "ok-b")
          )
        )
      )
    )
    val result = reviewAndFix(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewerA, reviewerB),
      task = "multi"
    )
    assertEquals(result.issues.map(_.issue).toSet, Set(issueA, issueB))
