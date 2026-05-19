package orca.review

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.{BackendTag, FlowContext, SessionId, TestFlowContext, Title}

import java.util.concurrent.atomic.AtomicReference

/** Integration-style test for the higher-level flow helpers (`reviewAndFixLoop`
  * / `fixLoop`) wired together inside a FlowContext that records every
  * OrcaEvent. Uses in-memory fakes for the LLM — no backend, no sbt, no
  * network.
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
      title = Title(desc),
      description = desc,
      file = None,
      line = None,
      suggestion = None
    )

  test("reviewAndFixLoop wraps the loop in a `Review & fix` stage"):
    val listener = new RecordingListener
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    val real = issue("real problem", confidence = 0.9)
    val reviewer = new FakeLlmTool(
      name = "perf",
      promptOutputs = List(ReviewResult(List(real)))
    )
    val coder = new FakeLlmTool(
      name = "coder",
      continueSessionOutputs = List(
        FixOutcome(Nil, List(IgnoredIssue(Title("real problem"), "trade-off")))
      )
    )

    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "optimize cache",
      initialDiff = Some("")
    )

    val events = listener.events
    assert(
      events.exists {
        case OrcaEvent.StageStarted("Review & fix") => true; case _ => false
      },
      s"missing StageStarted(Review & fix); got: $events"
    )
    assert(
      events.exists {
        case OrcaEvent.StageCompleted("Review & fix", _) => true;
        case _                                           => false
      },
      s"missing StageCompleted(Review & fix); got: $events"
    )

  test("max iterations path surfaces leftover issues with the cap reason"):
    val listener = new RecordingListener
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    // Reviewer keeps reporting the same issue every round; coder claims it
    // fixed it every round (so the loop sees progress) but the next eval
    // still finds it. The cap is the only thing that can stop this.
    // The first reviewer call is a startSession (consumes from
    // promptOutputs); each subsequent iteration is a continueSession.
    val stubborn = issue("never ends")
    val reviewer = new FakeLlmTool(
      name = "loud",
      promptOutputs = List(ReviewResult(List(stubborn))),
      continueSessionOutputs = List.fill(20)(ReviewResult(List(stubborn)))
    )
    val coder = new FakeLlmTool(
      name = "fixer",
      continueSessionOutputs =
        List.fill(20)(FixOutcome(List(Title("never ends")), Nil))
    )

    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "never ending",
      maxIterations = 2,
      initialDiff = Some("")
    )
    assert(
      result.issues.exists(_.reason.contains("max iterations")),
      s"expected a max-iterations reason; got: ${result.issues}"
    )
