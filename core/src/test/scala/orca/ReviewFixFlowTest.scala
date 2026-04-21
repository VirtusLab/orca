package orca

import java.util.concurrent.atomic.AtomicReference

/** Integration-style test for the Epic 8 flow helpers wired together inside a
  * FlowContext that records every OrcaEvent. Uses in-memory fakes for the LLM —
  * no backend, no sbt, no network.
  */
class ReviewFixFlowTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  private def issue(desc: String, confidence: Double = 1.0): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = confidence,
      description = desc,
      file = None,
      line = None,
      suggestion = None
    )

  test(
    "reviewAndFix wraps the loop in stage events and returns accumulated ignored issues"
  ):
    val listener = new RecordingListener
    val dispatcher = new EventDispatcher(List(listener))
    given FlowContext = new TestFlowContext(dispatcher)

    val real = issue("real problem", confidence = 0.9)
    val noise = issue("wishful", confidence = 0.3)

    // One reviewer flags both a real issue and a low-confidence nit on every
    // run; once the real issue is marked ignored, the filter below
    // confidenceThreshold drops the nit, so the loop terminates.
    val reviewer = new FakeLlmTool(
      name = "perf",
      promptOutputs = List.fill(5)(
        ReviewResult(List(real, noise), "2 findings")
      )
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs =
        List(IgnoredIssues(List(IgnoredIssue(real, "accepted as trade-off"))))
    )

    val result = reviewAndFix(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "optimize cache",
      confidenceThreshold = 0.7
    )

    assertEquals(
      result.issues.map(_.issue.description),
      List("real problem")
    )
    assertEquals(result.issues.map(_.reason), List("accepted as trade-off"))

    val events = listener.events
    val stageName = "Review & fix: optimize cache"
    assert(
      events.collectFirst {
        case OrcaEvent.StageStarted(n) if n == stageName => n
      }.isDefined,
      s"missing StageStarted($stageName), got: $events"
    )
    assert(
      events.collectFirst {
        case OrcaEvent.StageCompleted(n, _) if n == stageName => n
      }.isDefined,
      s"missing StageCompleted($stageName), got: $events"
    )

  test("max iterations path surfaces leftover issues with a visible reason"):
    val listener = new RecordingListener
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    // Reviewer keeps reporting a new fresh issue every time and coder keeps
    // accepting exactly one — maxIterations caps the loop with leftover.
    val allIssues = (1 to 100).map(n => issue(s"issue-$n"))
    val evalIt = allIssues.iterator
    val reviewer = new FakeLlmTool(
      name = "loud",
      promptOutputs = (1 to 20)
        .map(_ => ReviewResult(List(evalIt.next()), "next"))
        .toList
    )
    val coder = new FakeLlmTool(
      name = "fixer",
      continueSessionOutputs =
        List.fill(20)(IgnoredIssues(List(IgnoredIssue(issue("pass"), "ack"))))
    )

    val result = reviewAndFix(
      coder = coder,
      sessionId = SessionId[Backend.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "never ending"
    )

    val reasons = result.issues.map(_.reason).distinct
    assert(
      reasons.exists(_.contains("max iterations")) ||
        reasons.exists(_.contains("no progress")),
      s"expected a bailout reason, got: $reasons"
    )
