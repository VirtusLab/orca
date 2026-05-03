package orca.review

import orca.{
  AgentInput,
  Announce,
  AutonomousLlmCall,
  AutonomousTextCall,
  Backend,
  EventDispatcher,
  FlowContext,
  InteractiveLlmCall,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId,
  TestFlowContext,
  Title
}

/** Fake LlmCall whose `autonomous.run` and `autonomous.continueSession` each
  * return a scripted sequence of outputs — cast through `Any` because the trait
  * is generic over output type.
  */
class FakeLlmCall[O](
    runOutputs: Iterator[Any],
    continueSessionOutputs: Iterator[Any]
) extends LlmCall[Backend.ClaudeCode.type, O]:
  val autonomous: AutonomousLlmCall[Backend.ClaudeCode.type, O] =
    new AutonomousLlmCall[Backend.ClaudeCode.type, O]:
      def run[I: AgentInput](
          input: I,
          config: LlmConfig = LlmConfig.default
      ): O = runOutputs.next().asInstanceOf[O]
      def startSession[I: AgentInput](
          input: I,
          config: LlmConfig = LlmConfig.default
      ): (SessionId[Backend.ClaudeCode.type], O) = ???
      def continueSession[I: AgentInput](
          sessionId: SessionId[Backend.ClaudeCode.type],
          input: I,
          config: LlmConfig = LlmConfig.default
      ): O = continueSessionOutputs.next().asInstanceOf[O]
  def interactive: InteractiveLlmCall[Backend.ClaudeCode.type, O] = ???

class FakeLlmTool(
    override val name: String,
    promptOutputs: List[Any] = Nil,
    continueSessionOutputs: List[Any] = Nil
) extends LlmTool[Backend.ClaudeCode.type]:
  private val promptIt = promptOutputs.iterator
  private val continueIt = continueSessionOutputs.iterator

  def autonomous: AutonomousTextCall[Backend.ClaudeCode.type] = ???

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    new FakeLlmCall[O](promptIt, continueIt)

  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def withName(n: String): LlmTool[Backend.ClaudeCode.type] = this

class ReviewAndFixTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(desc: String, confidence: Double = 1.0): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = confidence,
      title = Title(desc),
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
        List(FixOutcome(Nil, List(IgnoredIssue(Title("real bug"), "accepted"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "build the widget",
      confidenceThreshold = 0.7
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("real bug"), "accepted"))
    )

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
          ignored = List(
            IgnoredIssue(Title("A"), "ok-a"),
            IgnoredIssue(Title("B"), "ok-b")
          )
        )
      )
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewerA, reviewerB),
      task = "multi"
    )
    assertEquals(result.issues.map(_.title).toSet, Set(Title("A"), Title("B")))

  test("ReviewerSelector.llmDriven asks the LLM once and caches"):
    val perf = new FakeLlmTool(name = "performance")
    val style = new FakeLlmTool(name = "readability")
    val coverage = new FakeLlmTool(name = "test-coverage")
    val all = List(perf, style, coverage)
    // Single reply — if the selector calls the LLM more than once the iterator
    // will throw NoSuchElement on the second call, failing the test.
    val picker = new FakeLlmTool(
      name = "picker",
      promptOutputs =
        List(SelectedReviewers(List("performance", "test-coverage")))
    )
    val select = ReviewerSelector.llmDriven(
      llm = picker,
      taskTitle = Title("optimize hot path"),
      changedFiles = List("src/Cache.scala")
    )
    assertEquals(
      select(Nil, all).map(_.name),
      List("performance", "test-coverage")
    )
    // Second call with a populated history (matches what reviewAndFixLoop would
    // pass on iteration 2) reuses the cached selection — no second LLM call.
    val fakeBatch = ReviewBatch(Nil)
    assertEquals(
      select(List(fakeBatch), all).map(_.name),
      List("performance", "test-coverage")
    )
