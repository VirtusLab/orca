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
  TestFlowContext
}

/** Fake LlmCall whose `autonomous` and `continueSession` each return a scripted
  * sequence of outputs — cast through `Any` because the trait is generic over
  * output type.
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

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    new FakeLlmCall[O](promptIt, continueIt)

  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String = ""
  def startSession(
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], String) = (SessionId("s"), "")
  def continueSession(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): String = ""
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this

class ReviewAndFixTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(desc: String, confidence: Double = 1.0): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = confidence,
      title = desc,
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
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(silentReviewer),
      task = "do the thing"
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("filters issues below the confidence threshold"):
    given FlowContext = ctx
    // Reviewer reports two issues every round; only the high-confidence one
    // survives the threshold and reaches the coder, which ignores it without
    // a fix. With `fixed` empty the loop halts after one round.
    val noisyIssue = issue("flaky", confidence = 0.3)
    val realIssue = issue("real bug", confidence = 0.95)
    val reviewer = new FakeLlmTool(
      name = "loud",
      promptOutputs = List(ReviewResult(List(noisyIssue, realIssue)))
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs =
        List(FixOutcome(Nil, List(IgnoredIssue("real bug", "accepted"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "build the widget",
      confidenceThreshold = 0.7
    )
    assertEquals(result.issues, List(IgnoredIssue("real bug", "accepted")))

  test("runs multiple reviewers and merges their issues"):
    given FlowContext = ctx
    val issueA = issue("A")
    val issueB = issue("B")
    val reviewerA = new FakeLlmTool(
      name = "a",
      promptOutputs = List(ReviewResult(List(issueA)))
    )
    val reviewerB = new FakeLlmTool(
      name = "b",
      promptOutputs = List(ReviewResult(List(issueB)))
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs = List(
        FixOutcome(
          fixed = Nil,
          ignored = List(IgnoredIssue("A", "ok-a"), IgnoredIssue("B", "ok-b"))
        )
      )
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewerA, reviewerB),
      task = "multi"
    )
    assertEquals(result.issues.map(_.title).toSet, Set("A", "B"))
