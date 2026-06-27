package orca.review

import orca.{FlowContext}
import orca.plan.Title
import orca.agents.{BackendTag, SessionId}
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.{TestFlowContext}

import java.util.concurrent.atomic.AtomicReference

/** Integration-style test for the higher-level flow helpers (`reviewAndFixLoop`
  * / `fixLoop`) wired together inside a FlowContext that records every
  * OrcaEvent. Uses in-memory fakes for the LLM — no backend, no sbt, no
  * network.
  */
class ReviewFixFlowTest extends munit.FunSuite:

  // `reviewAndFixLoop` is now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

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

  test("reviewAndFixLoop marks the loop with a `Review & fix` progress line"):
    val listener = new RecordingListener
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    val real = issue("real problem", confidence = 0.9)
    val reviewer = new FakeAgent(
      name = "perf",
      outputs = List(ReviewResult(List(real)))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(Nil, List(IgnoredIssue(Title("real problem"), "trade-off")))
      )
    )

    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "optimize cache",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )

    // The loop now runs under the caller's task stage (ADR 0018 §2.2), so it
    // emits a progress line rather than opening its own committing stage.
    val events = listener.events
    assert(
      events.exists {
        case OrcaEvent.Step("Review & fix") => true; case _ => false
      },
      s"missing Step(Review & fix); got: $events"
    )

  test("max iterations path surfaces leftover issues with the cap reason"):
    val listener = new RecordingListener
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    // Reviewer keeps reporting the same issue every round; coder claims it
    // fixed it every round (so the loop sees progress) but the next eval
    // still finds it. The cap is the only thing that can stop this.
    // Reviewer keeps reporting the same issue across all iterations.
    val stubborn = issue("never ends")
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List.fill(21)(ReviewResult(List(stubborn)))
    )
    val coder = new FakeAgent(
      name = "fixer",
      outputs = List.fill(20)(FixOutcome(List(Title("never ends")), Nil))
    )

    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "never ending",
      maxIterations = 2,
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assert(
      result.issues.exists(_.reason.contains("max iterations")),
      s"expected a max-iterations reason; got: ${result.issues}"
    )
