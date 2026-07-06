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
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener, Usage}
import orca.{TestFlowContext}

/** Fake AgentCall whose `autonomous.run` drains a scripted sequence of outputs
  * in order — cast through `Any` because the trait is generic over output type.
  * The session id from the call site is echoed back so tests can verify the
  * loop threaded a consistent id; `seenSessions` records each call's session id
  * so tests can assert "fresh on first, same id thereafter."
  */
class FakeAgentCall[O](outputs: Iterator[Any])
    extends AgentCall[BackendTag.ClaudeCode.type, O]:

  /** Session ids the LLM was called with, in invocation order. */
  val seenSessions = new java.util.concurrent.atomic.AtomicReference[
    List[SessionId[BackendTag.ClaudeCode.type]]
  ](Nil)

  val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
    new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
      def run[I: AgentInput](
          input: I,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], O) =
        val _ = seenSessions.updateAndGet(session :: _)
        (session, outputs.next().asInstanceOf[O])
  def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] = ???

class FakeAgent(
    override val name: String,
    outputs: List[Any] = Nil
) extends Agent[BackendTag.ClaudeCode.type]:
  private val it = outputs.iterator
  val fakeCall: FakeAgentCall[Any] = new FakeAgentCall[Any](it)

  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???

  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    fakeCall.asInstanceOf[AgentCall[BackendTag.ClaudeCode.type, O]]

  /** Session ids this tool was called with, in invocation order. Tests assert
    * the loop threaded a stable id across iterations.
    */
  def seenSessions: List[SessionId[BackendTag.ClaudeCode.type]] =
    fakeCall.seenSessions.get().reverse

  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this

/** A reviewer stub that emits a `TokensUsed` event carrying the name captured
  * at `resultAs` time — mirroring `BaseAgent`, whose `resultAs` snapshots
  * `name` for the cost axis. `withName` returns a renamed copy, so the copy the
  * loop makes at its emission edge reports the prefixed name.
  */
private class TokenEmittingReviewer(
    override val name: String,
    result: ReviewResult
)(using ctx: FlowContext)
    extends Agent[BackendTag.ClaudeCode.type]:
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] =
    new TokenEmittingReviewer(n, result)
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    val capturedName = name
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          def run[I: AgentInput](
              i: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): (SessionId[BackendTag.ClaudeCode.type], O) =
            ctx.emit(OrcaEvent.TokensUsed(capturedName, None, Usage.empty))
            (session, result.asInstanceOf[O])
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        ???

class ReviewAndFixTest extends munit.FunSuite:

  // `reviewAndFixLoop` is now gated on `InStage`; mint the token for the suite.
  private given orca.InStage = orca.InStage.unsafe

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
    val silentReviewer = new FakeAgent(
      name = "quiet",
      outputs = List(ReviewResult.empty)
    )
    val coder = new FakeAgent("coder")
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(silentReviewer),
      task = "do the thing",
      reviewerSelection = ReviewerSelector.allEveryRound,
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
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(ReviewResult(List(noisyIssue, realIssue)))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs =
        List(FixOutcome(Nil, List(IgnoredIssue(Title("real bug"), "accepted"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "build the widget",
      confidenceThreshold = 0.7,
      reviewerSelection = ReviewerSelector.allEveryRound,
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
    val reviewerA = new FakeAgent(
      name = "a",
      outputs = List(ReviewResult(List(issueA)))
    )
    val reviewerB = new FakeAgent(
      name = "b",
      outputs = List(ReviewResult(List(issueB)))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
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
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(result.issues.map(_.title).toSet, Set(Title("A"), Title("B")))

  test(
    "reviewer is called with the same session id on every iteration"
  ):
    // Pins the cross-iteration session-threading contract: a reviewer's
    // first call mints a session via `r.newSession`, and every subsequent
    // call resumes the SAME id. Without this the loop could lose context
    // across iterations.
    given FlowContext = ctx
    val stubborn = issue("never ends")
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List.fill(4)(ReviewResult(List(stubborn)))
    )
    val coder = new FakeAgent(
      name = "fixer",
      outputs = List.fill(3)(FixOutcome(List(Title("never ends")), Nil))
    )
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "never ending",
      maxIterations = 2,
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    val reviewerSessions = reviewer.seenSessions
    assert(
      reviewerSessions.size >= 2,
      s"expected ≥ 2 reviewer calls, got $reviewerSessions"
    )
    assertEquals(
      reviewerSessions.distinct.size,
      1,
      s"reviewer must reuse one session across iterations; got ${reviewerSessions.map(SessionId.value)}"
    )

  test("initialDiff is embedded in the reviewer's first prompt"):
    given FlowContext = ctx
    var capturedFirst: Option[String] = None
    val captureReviewer = new Agent[BackendTag.ClaudeCode.type]:
      val name = "capturing"
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
      def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
      def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] =
        new AgentCall[BackendTag.ClaudeCode.type, O]:
          val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
            new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
              def run[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): (SessionId[BackendTag.ClaudeCode.type], O) =
                capturedFirst = Some(i.toString)
                (
                  SessionId[BackendTag.ClaudeCode.type]("s"),
                  ReviewResult.empty.asInstanceOf[O]
                )
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(captureReviewer),
      task = "do thing",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("--- a/Foo.scala\n+++ b/Foo.scala\n+ added line")
    )
    val sent =
      capturedFirst.getOrElse(fail("the fresh-session run was never called"))
    assert(sent.contains("--- a/Foo.scala"), s"diff missing from prompt: $sent")
    assert(sent.contains("do thing"), s"task missing from prompt: $sent")

  test(
    "an agentDriven reviewerSelection narrows the active set via its picker LLM"
  ):
    given FlowContext = ctx
    val issueX = issue("only-x", confidence = 0.9)
    val reviewerX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issueX)))
    )
    val reviewerY = new FakeAgent(
      name = "y"
      // promptOutputs intentionally empty: if the picker mistakenly chose y,
      // the loop would hit an empty iterator and throw.
    )
    val picker = new FakeAgent(
      name = "picker",
      outputs = List(SelectedReviewers(List("x")))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs =
        List(FixOutcome(Nil, List(IgnoredIssue(Title("only-x"), "accepted"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewerX, reviewerY),
      reviewerSelection = ReviewerSelector.agentDriven(agent = picker),
      task = "picker-routing check",
      initialDiff = Some("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("only-x"), "accepted"))
    )

  test(
    "explicit allEveryRound reviewerSelection skips the LLM picker entirely"
  ):
    given FlowContext = ctx
    val issueX = issue("only-x", confidence = 0.9)
    val reviewerX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issueX)))
    )
    // The coder's promptOutputs is empty: if the loop wrongly invokes the
    // picker against `coder`, the empty iterator throws and the test fails.
    val coder = new FakeAgent(
      name = "coder",
      outputs =
        List(FixOutcome(Nil, List(IgnoredIssue(Title("only-x"), "accepted"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewerX),
      task = "no-picker check",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("only-x"), "accepted"))
    )

  test("each agent's Step lands on listeners as it finishes, not at end"):
    // Two reviewers gated on latches we control: gate2 releases first, so
    // the second reviewer must finish first; its Step must be visible to a
    // listener BEFORE the slower first reviewer's Step. A serialised
    // (collect-then-emit) implementation would emit them in configured
    // order regardless of completion — this test would fail.
    val gate1 = new java.util.concurrent.CountDownLatch(1)
    val gate2 = new java.util.concurrent.CountDownLatch(1)
    val firstStepAt =
      new java.util.concurrent.atomic.AtomicReference[String]("")
    val secondStepFinishedLatch = new java.util.concurrent.CountDownLatch(1)
    val listener: OrcaListener = (e: OrcaEvent) =>
      e match
        case OrcaEvent.Step(msg) if msg.contains("slow:") =>
          val _ = firstStepAt.compareAndSet("", "slow")
        case OrcaEvent.Step(msg) if msg.contains("fast:") =>
          val _ = firstStepAt.compareAndSet("", "fast")
          secondStepFinishedLatch.countDown()
        case _ => ()
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))

    class GatedReviewer(
        label: String,
        gate: java.util.concurrent.CountDownLatch
    ) extends Agent[BackendTag.ClaudeCode.type]:
      val name = label
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
      def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
      def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] =
        new AgentCall[BackendTag.ClaudeCode.type, O]:
          val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
            new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
              def run[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): (SessionId[BackendTag.ClaudeCode.type], O) =
                val ok = gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
                assert(ok, s"$label gate never opened")
                (
                  SessionId[BackendTag.ClaudeCode.type](s"sid-$label"),
                  ReviewResult.empty.asInstanceOf[O]
                )
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val slow = new GatedReviewer("slow", gate1)
    val fast = new GatedReviewer("fast", gate2)
    val runner = new Thread(() =>
      val _ = reviewAndFixLoop(
        coder = new FakeAgent("coder"),
        sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
        reviewers = List(slow, fast),
        task = "ordering check",
        reviewerSelection = ReviewerSelector.allEveryRound,
        initialDiff = Some("")
      )
    )
    runner.start()
    // Release the second reviewer first, wait for its Step, then release the
    // first one — proves the fast finisher emits without being held back.
    gate2.countDown()
    val gotFast = secondStepFinishedLatch.await(
      2,
      java.util.concurrent.TimeUnit.SECONDS
    )
    assert(gotFast, "the fast reviewer's Step never reached the listener")
    assertEquals(
      firstStepAt.get(),
      "fast",
      "expected the fast reviewer's Step to land first, not the slow one's"
    )
    gate1.countDown()
    runner.join(5000)

  test("lint runs concurrently with reviewers (deterministic via latch)"):
    given FlowContext = ctx
    // Two-party rendezvous: each branch counts down on entry and awaits the
    // other. If the loop runs them sequentially the second branch never
    // starts (first is blocked on await) — the awaits time out and the test
    // fails. Concurrent execution releases both and proceeds.
    val rendezvous = new java.util.concurrent.CountDownLatch(2)
    val timeoutMs = 2000L

    class RendezvousReviewer(label: String)
        extends Agent[BackendTag.ClaudeCode.type]:
      val name = label
      def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
      def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
      def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] =
        this
      def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
      def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
      def resultAs[O: JsonData: Announce]
          : AgentCall[BackendTag.ClaudeCode.type, O] =
        new AgentCall[BackendTag.ClaudeCode.type, O]:
          val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
            new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
              private def rendezvousThen(): O =
                rendezvous.countDown()
                val ok = rendezvous.await(
                  timeoutMs,
                  java.util.concurrent.TimeUnit.MILLISECONDS
                )
                if !ok then
                  fail(
                    s"$label timed out waiting for the other branch — " +
                      "they ran sequentially"
                  )
                ReviewResult.empty.asInstanceOf[O]
              def run[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): (SessionId[BackendTag.ClaudeCode.type], O) =
                (
                  SessionId[BackendTag.ClaudeCode.type](s"sid-$label"),
                  rendezvousThen()
                )
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val _ = reviewAndFixLoop(
      coder = new FakeAgent("coder"),
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(new RendezvousReviewer("reviewer")),
      task = "concurrency check",
      // echo emits output so `lint` doesn't short-circuit on empty stdout
      // and actually calls the (rendezvousing) LLM summariser.
      lintCommand = Some("echo lint-output"),
      lintAgent = Some(new RendezvousReviewer("lint")),
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )

  test("formatCommand runs before every review round (impl + each fix)"):
    given FlowContext = ctx
    // The formatter appends one line per run. Two review rounds (issue → fix,
    // then clean) mean it must run twice — once before reviewing the
    // implementation, once before re-reviewing the fix.
    val counter = os.temp.dir() / "fmt-count"
    val reviewer = new FakeAgent(
      name = "r",
      outputs =
        List(ReviewResult(List(issue("needs fixing"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(List(Title("needs fixing")), Nil))
    )
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "format check",
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommand = Some(s"echo x >> '$counter'"),
      initialDiff = Some("")
    )
    val runs = if os.exists(counter) then os.read.lines(counter).size else 0
    assertEquals(runs, 2)

  test("reviewer LLM runs are labelled with the cost prefix"):
    // The loop keeps reviewer identity as the bare slug but runs the LLM under a
    // `reviewer: <slug>` copy so `CostTracker` can group the spend. Assert the
    // emitted `TokensUsed.agent` still carries the prefix.
    val recorded =
      new java.util.concurrent.ConcurrentLinkedQueue[OrcaEvent.TokensUsed]()
    val listener: OrcaListener =
      case t: OrcaEvent.TokensUsed => recorded.add(t): Unit
      case _                       => ()
    given FlowContext =
      new TestFlowContext(new EventDispatcher(List(listener)))
    val reviewer = new TokenEmittingReviewer("performance", ReviewResult.empty)
    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(reviewer),
      task = "cost labelling",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    val agents = recorded.toArray.toList.collect:
      case t: OrcaEvent.TokensUsed => t.agent
    assertEquals(agents, List("reviewer: performance"))

  test(
    "a selector returning a same-named foreign agent runs the roster instance"
  ):
    // The selector hands back a rebuilt copy sharing a roster slug but backed by
    // a different (empty-output) stub. Roster resolution must map it back to the
    // canonical instance: the roster stub runs (its scripted issue flows out),
    // and the foreign stub is never touched (its empty iterator would throw).
    given FlowContext = ctx
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issue("from-roster", confidence = 0.9))))
    )
    val foreignX = new FakeAgent(name = "x") // no outputs: throws if run
    val foreignSelector = new ReviewerSelector:
      def prepare(
          all: List[Agent[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage): List[ReviewBatch] => List[Agent[?]] =
        _ => List(foreignX)
    val coder = new FakeAgent(
      name = "coder",
      outputs =
        List(FixOutcome(Nil, List(IgnoredIssue(Title("from-roster"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(rosterX),
      reviewerSelection = foreignSelector,
      task = "roster resolution",
      initialDiff = Some("")
    )
    assertEquals(result.issues, List(IgnoredIssue(Title("from-roster"), "ok")))
    assert(
      rosterX.seenSessions.nonEmpty,
      "the canonical roster instance must have been the one that ran"
    )

  test(
    "an all-foreign selection warns and falls back to the full roster, not zero"
  ):
    // Every selected name is outside the roster (e.g. a pre-rename custom
    // selector still building `reviewer: <slug>` names). Rather than run zero
    // reviewers and ship unreviewed code, the safety floor falls back to the
    // full roster: both members run, the foreign name is dropped with a
    // visible Step, and the foreign stub is never touched.
    val steps =
      new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.Step(msg) => steps.add(msg): Unit
      case _                   => ()
    given FlowContext =
      new TestFlowContext(new EventDispatcher(List(listener)))
    val stranger =
      new FakeAgent(name = "stranger") // never run: throws if it is
    val rosterA = new FakeAgent(
      name = "a",
      outputs = List(ReviewResult(List(issue("from-a", confidence = 0.9))))
    )
    val rosterB = new FakeAgent(
      name = "b",
      outputs = List(ReviewResult(List(issue("from-b", confidence = 0.9))))
    )
    val strangerSelector = new ReviewerSelector:
      def prepare(
          all: List[Agent[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage): List[ReviewBatch] => List[Agent[?]] =
        _ => List(stranger)
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          Nil,
          List(
            IgnoredIssue(Title("from-a"), "ok"),
            IgnoredIssue(Title("from-b"), "ok")
          )
        )
      )
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(rosterA, rosterB),
      reviewerSelection = strangerSelector,
      task = "all-foreign floor",
      initialDiff = Some("")
    )
    assert(rosterA.seenSessions.nonEmpty, "roster A must run under the floor")
    assert(rosterB.seenSessions.nonEmpty, "roster B must run under the floor")
    assert(
      stranger.seenSessions.isEmpty,
      "the foreign agent must not have been run"
    )
    assert(
      steps.toArray.toList.exists(m =>
        m.asInstanceOf[String].contains("stranger") &&
          m.asInstanceOf[String].contains("not in the configured roster")
      ),
      s"expected a drop warning naming 'stranger'; got ${steps.toArray.toList}"
    )
    assert(
      steps.toArray.toList.exists(m =>
        m.asInstanceOf[String].contains("falling back to all")
      ),
      s"expected a fallback warning; got ${steps.toArray.toList}"
    )
    assertEquals(
      result.issues.map(_.title).toSet,
      Set(Title("from-a"), Title("from-b"))
    )

  test("a duplicate same-slug selection runs the reviewer once that round"):
    // The selector returns the same roster instance twice; `distinctBy` collapses
    // it so the reviewer runs a single time (one session, one scripted output —
    // a second run would drain its empty iterator and throw).
    given FlowContext = ctx
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issue("from-x", confidence = 0.9))))
    )
    val dupSelector = new ReviewerSelector:
      def prepare(
          all: List[Agent[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage): List[ReviewBatch] => List[Agent[?]] =
        _ => List(rosterX, rosterX)
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(Nil, List(IgnoredIssue(Title("from-x"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(rosterX),
      reviewerSelection = dupSelector,
      task = "duplicate selection",
      initialDiff = Some("")
    )
    assertEquals(rosterX.seenSessions.size, 1)
    assertEquals(result.issues, List(IgnoredIssue(Title("from-x"), "ok")))

  test(
    "roster resolution resumes one session across rounds despite a foreign copy"
  ):
    // Round 1 the selector returns the roster instance; round 2 it returns a
    // FOREIGN same-slug copy. Both resolve to the canonical instance, so the
    // reviewer RESUMES its single session on round 2 rather than minting a new
    // one. Round 1's fix keeps the loop going; round 2 stops it.
    given FlowContext = ctx
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(
        ReviewResult(List(issue("round-1", confidence = 0.9))),
        ReviewResult(List(issue("round-2", confidence = 0.9)))
      )
    )
    val foreignX = new FakeAgent(name = "x") // no outputs: throws if ever run
    val twoRoundSelector = new ReviewerSelector:
      def prepare(
          all: List[Agent[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage): List[ReviewBatch] => List[Agent[?]] =
        history => if history.isEmpty then List(rosterX) else List(foreignX)
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(List(Title("round-1")), Nil),
        FixOutcome(Nil, List(IgnoredIssue(Title("round-2"), "ok")))
      )
    )
    val result = reviewAndFixLoop(
      coder = coder,
      sessionId = SessionId[BackendTag.ClaudeCode.type]("s"),
      reviewers = List(rosterX),
      reviewerSelection = twoRoundSelector,
      task = "two-round resume",
      initialDiff = Some("")
    )
    assertEquals(
      rosterX.seenSessions.size,
      2,
      s"reviewer must run in both rounds; got ${rosterX.seenSessions}"
    )
    assertEquals(
      rosterX.seenSessions.distinct.size,
      1,
      s"round 2 must resume the round-1 session; got ${rosterX.seenSessions
          .map(SessionId.value)}"
    )
    assert(
      foreignX.seenSessions.isEmpty,
      "the foreign same-slug copy must never run"
    )
    assertEquals(result.issues, List(IgnoredIssue(Title("round-2"), "ok")))
