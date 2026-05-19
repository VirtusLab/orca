package orca.review

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
import orca.{FlowContext, TestFlowContext}

/** Fake LlmCall whose `autonomous.run` / `autonomous.startSession` /
  * `autonomous.continueSession` return scripted sequences of outputs — cast
  * through `Any` because the trait is generic over output type.
  *
  * `runOutputs` feeds both `run` and `startSession` (the first call in a
  * structured-session shape consumes from this list); `continueSessionOutputs`
  * feeds `continueSession` for follow-up calls.
  */
class FakeLlmCall[O](
    runOutputs: Iterator[Any],
    continueSessionOutputs: Iterator[Any]
) extends LlmCall[BackendTag.ClaudeCode.type, O]:
  // A shared counter so each startSession produces a distinct session id,
  // letting tests assert on which id flows where.
  private val sessionCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
    new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
      def run[I: AgentInput](
          input: I,
          config: LlmConfig = LlmConfig.default
      ): O = runOutputs.next().asInstanceOf[O]
      def startSession[I: AgentInput](
          input: I,
          config: LlmConfig = LlmConfig.default
      ): (SessionId[BackendTag.ClaudeCode.type], O) =
        val sid = SessionId[BackendTag.ClaudeCode.type](
          s"fake-session-${sessionCounter.incrementAndGet()}"
        )
        (sid, runOutputs.next().asInstanceOf[O])
      def continueSession[I: AgentInput](
          sessionId: SessionId[BackendTag.ClaudeCode.type],
          input: I,
          config: LlmConfig = LlmConfig.default
      ): O = continueSessionOutputs.next().asInstanceOf[O]
  def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] = ???

class FakeLlmTool(
    override val name: String,
    promptOutputs: List[Any] = Nil,
    continueSessionOutputs: List[Any] = Nil
) extends LlmTool[BackendTag.ClaudeCode.type]:
  private val promptIt = promptOutputs.iterator
  private val continueIt = continueSessionOutputs.iterator

  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???

  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] =
    new FakeLlmCall[O](promptIt, continueIt)

  def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
  def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this

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
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(silentReviewer),
      task = "do the thing",
      initialDiff = Some("")
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
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "build the widget",
      confidenceThreshold = 0.7,
      initialDiff = Some("")
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
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewerA, reviewerB),
      task = "multi",
      initialDiff = Some("")
    )
    assertEquals(result.issues.map(_.title).toSet, Set(Title("A"), Title("B")))

  test(
    "first reviewer call uses startSession; subsequent iterations continueSession"
  ):
    given FlowContext = ctx
    val stubborn = issue("never ends")
    // promptOutputs has exactly one entry — proves the first iteration
    // consumes from startSession, not run. continueSessionOutputs supplies
    // the follow-up iterations.
    val reviewer = new FakeLlmTool(
      name = "loud",
      promptOutputs = List(ReviewResult(List(stubborn))),
      continueSessionOutputs = List.fill(3)(ReviewResult(List(stubborn)))
    )
    val coder = new FakeLlmTool(
      name = "fixer",
      continueSessionOutputs =
        List.fill(3)(FixOutcome(List(Title("never ends")), Nil))
    )
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "never ending",
      maxIterations = 2,
      initialDiff = Some("")
    )
    // If the loop had used `run` for the first call, the empty
    // `runOutputs` iterator would have thrown NoSuchElement before the
    // continueSessionOutputs were ever drained — passing this test
    // confirms the session-based path was taken.

  test("initialDiff is embedded in the reviewer's first prompt"):
    given FlowContext = ctx
    var capturedFirst: Option[String] = None
    val captureReviewer = new LlmTool[BackendTag.ClaudeCode.type]:
      val name = "capturing"
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
      def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : LlmCall[BackendTag.ClaudeCode.type, O] =
        new LlmCall[BackendTag.ClaudeCode.type, O]:
          val autonomous: AutonomousLlmCall[BackendTag.ClaudeCode.type, O] =
            new AutonomousLlmCall[BackendTag.ClaudeCode.type, O]:
              def run[I: AgentInput](
                  i: I,
                  c: LlmConfig = LlmConfig.default
              ): O = ???
              def startSession[I: AgentInput](
                  i: I,
                  c: LlmConfig = LlmConfig.default
              ): (SessionId[BackendTag.ClaudeCode.type], O) =
                capturedFirst = Some(i.toString)
                (
                  SessionId[BackendTag.ClaudeCode.type]("s"),
                  ReviewResult.empty.asInstanceOf[O]
                )
              def continueSession[I: AgentInput](
                  s: SessionId[BackendTag.ClaudeCode.type],
                  i: I,
                  c: LlmConfig = LlmConfig.default
              ): O = ???
          def interactive: InteractiveLlmCall[BackendTag.ClaudeCode.type, O] =
            ???

    val coder = new FakeLlmTool("coder")
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(captureReviewer),
      task = "do thing",
      initialDiff = Some("--- a/Foo.scala\n+++ b/Foo.scala\n+ added line")
    )
    val sent = capturedFirst.getOrElse(fail("startSession was never called"))
    assert(sent.contains("--- a/Foo.scala"), s"diff missing from prompt: $sent")
    assert(sent.contains("do thing"), s"task missing from prompt: $sent")

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
