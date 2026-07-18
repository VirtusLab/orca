package orca.review

import orca.{Configured, FlowContext, FlowControl, StackSettings}
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
  ToolSet,
  WireSessionId
}
import orca.backend.{IdScheme, SessionSupport}
import orca.progress.SessionRecord
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener, Usage}
import orca.testkit.TempDirs

/** Fake AgentCall whose `autonomous.run` drains a scripted sequence of outputs
  * in order. `seenSessions` records each call's session id so tests can assert
  * "fresh on first, same id thereafter."
  */
class FakeAgentCall[O](outputs: Iterator[Any])
    extends AgentCall[BackendTag.ClaudeCode.type, O]:

  /** Session ids the LLM was called with, in invocation order. */
  val seenSessions = new java.util.concurrent.atomic.AtomicReference[
    List[SessionId[BackendTag.ClaudeCode.type]]
  ](Nil)

  val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
    new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
      private[orca] def runWithSession[I: AgentInput](
          input: I,
          session: SessionId[BackendTag.ClaudeCode.type],
          config: Option[AgentConfig],
          emitPrompt: Boolean
      )(using orca.InStage): O =
        val _ = seenSessions.updateAndGet(session :: _)
        outputs.next().asInstanceOf[O]
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

/** A reviewer stub that emits a `TokensUsed` event carrying the name + role
  * captured at `resultAs` time, mirroring `BaseAgent`. `withRole` returns a
  * role-tagged copy with `name` unchanged.
  */
private class TokenEmittingReviewer(
    override val name: String,
    result: ReviewResult,
    override val role: Option[String] = None
)(using ctx: FlowContext)
    extends Agent[BackendTag.ClaudeCode.type]:
  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] =
    new TokenEmittingReviewer(n, result, role)
  override def withRole(r: String): Agent[BackendTag.ClaudeCode.type] =
    new TokenEmittingReviewer(name, result, Some(r))
  def withTools(tools: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    val capturedName = name
    val capturedRole = role
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              i: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): O =
            ctx.emit(
              OrcaEvent
                .TokensUsed(capturedName, None, Usage.empty, capturedRole)
            )
            result.asInstanceOf[O]
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        ???

/** A coder stub for the fix-turn seeding test: captures the prompt its
  * structured `run` receives and drives `willContinue` via a real durable
  * [[SessionSupport]], so a test can exercise both the fresh (re-seed) and live
  * (no re-seed) branches of the fix turn. Always returns `fixOutcome`.
  */
private class SeedProbingCoder(
    existsResult: Boolean,
    fixOutcome: FixOutcome
) extends Agent[BackendTag.ClaudeCode.type]:
  val name: String = "coder"

  @volatile var capturedFixPrompt: Option[String] = None

  // A fresh support per access, registered only when `existsResult` so the
  // mapping-gated probe returns `existsResult`.
  override private[orca] def sessionSupport
      : Option[SessionSupport[BackendTag.ClaudeCode.type]] =
    val support = SessionSupport.durable[BackendTag.ClaudeCode.type](
      IdScheme.ServerMinted,
      _ => existsResult
    )
    if existsResult then
      support.register(
        SessionId[BackendTag.ClaudeCode.type]("s"),
        WireSessionId[BackendTag.ClaudeCode.type]("wire-s")
      )
    Some(support)

  def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] = ???
  def withConfig(c: AgentConfig): Agent[BackendTag.ClaudeCode.type] = this
  def withSystemPrompt(p: String): Agent[BackendTag.ClaudeCode.type] = this
  def withName(n: String): Agent[BackendTag.ClaudeCode.type] = this
  def withTools(t: ToolSet): Agent[BackendTag.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              config: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): O =
            capturedFixPrompt = Some(summon[AgentInput[I]].serialize(input))
            fixOutcome.asInstanceOf[O]
      def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
        ???

class ReviewAndFixTest extends munit.FunSuite:

  // `reviewAndFixLoop` is gated on `InStage` + `WorkspaceWrite` (ADR 0018 §6);
  // mint both for the suite.
  private given orca.InStage = orca.InStage.unsafe
  private given orca.WorkspaceWrite = orca.WorkspaceWrite.unsafe

  private def control: FlowControl =
    ReviewLoopFixture.control(new EventDispatcher(Nil))

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
    given FlowControl = control
    val silentReviewer = new FakeAgent(
      name = "quiet",
      outputs = List(ReviewResult.empty)
    )
    val coder = new FakeAgent("coder")
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(silentReviewer),
      task = "do the thing",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("filters issues below the confidence threshold"):
    given FlowControl = control
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
      coderSession = ReviewLoopFixture.coderSession(coder),
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
    given FlowControl = control
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
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewerA, reviewerB),
      task = "multi",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(result.issues.map(_.title).toSet, Set(Title("A"), Title("B")))

  test(
    "reviewer is called with the same session id on every iteration"
  ):
    // Cross-iteration session-threading contract: a reviewer's first call mints
    // its own chat, and every subsequent call resumes the SAME conversation.
    given FlowControl = control
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
      coderSession = ReviewLoopFixture.coderSession(coder),
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
    given FlowControl = control
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
              private[orca] def runWithSession[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): O =
                capturedFirst = Some(i.toString)
                ReviewResult.empty.asInstanceOf[O]
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
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
    given FlowControl = control
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
      coderSession = ReviewLoopFixture.coderSession(coder),
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
    "omitting reviewerSelection defaults to an agentDriven picker on the lead's cheap tier"
  ):
    // With no reviewerSelection the default `ReviewerSelector.agentDriven`
    // resolves its picker as the review role's cheap tier (`ctx.reviewAgent.cheap`).
    // The control wires the coder as the lead, and FakeAgent.cheap == this, so
    // the default picker draws the reviewer pick from the coder's outputs
    // (then the fix) — proving selection routed through the context's lead.
    // "y" (empty outputs) would throw if the picker failed to narrow it out.
    val issueX = issue("only-x", confidence = 0.9)
    val reviewerX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issueX)))
    )
    val reviewerY = new FakeAgent(name = "y")
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        SelectedReviewers(List("x")),
        FixOutcome(Nil, List(IgnoredIssue(Title("only-x"), "accepted")))
      )
    )
    given FlowControl =
      ReviewLoopFixture.control(new EventDispatcher(Nil), lead = Some(coder))
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewerX, reviewerY),
      task = "default selection",
      initialDiff = Some("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("only-x"), "accepted"))
    )
    assert(reviewerX.seenSessions.nonEmpty, "the picked reviewer must run")
    assert(
      reviewerY.seenSessions.isEmpty,
      "the default picker must narrow out the unpicked reviewer"
    )

  test(
    "explicit allEveryRound reviewerSelection skips the LLM picker entirely"
  ):
    given FlowControl = control
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
      coderSession = ReviewLoopFixture.coderSession(coder),
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
    given FlowControl =
      ReviewLoopFixture.control(new EventDispatcher(List(listener)))

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
              private[orca] def runWithSession[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): O =
                val ok = gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
                assert(ok, s"$label gate never opened")
                ReviewResult.empty.asInstanceOf[O]
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val slow = new GatedReviewer("slow", gate1)
    val fast = new GatedReviewer("fast", gate2)
    val runner = new Thread(() =>
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
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
    given FlowControl = control
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
              private[orca] def runWithSession[I: AgentInput](
                  i: I,
                  session: SessionId[BackendTag.ClaudeCode.type],
                  c: Option[AgentConfig],
                  emitPrompt: Boolean
              )(using
                  orca.InStage
              ): O =
                rendezvousThen()
          def interactive: InteractiveAgentCall[BackendTag.ClaudeCode.type, O] =
            ???

    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(new RendezvousReviewer("reviewer")),
      task = "concurrency check",
      // echo emits output so `lint` doesn't short-circuit on empty stdout
      // and actually calls the (rendezvousing) LLM summariser.
      lint = Configured.Use(
        Lint(List("echo lint-output"), new RendezvousReviewer("lint"))
      ),
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )

  test("formatCommands run before every review round (impl + each fix)"):
    given FlowControl = control
    // The formatter appends one line per run. Two review rounds (issue → fix,
    // then clean) mean it must run twice — once before reviewing the
    // implementation, once before re-reviewing the fix.
    val counter = TempDirs.dir() / "fmt-count"
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
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "format check",
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List(s"echo x >> '$counter'")),
      initialDiff = Some("")
    )
    val runs = if os.exists(counter) then os.read.lines(counter).size else 0
    assertEquals(runs, 2)

  test("a failing format command doesn't stop the ones after it"):
    given FlowControl = control
    // `false` exits nonzero; the loop is fail-open on format commands, so the
    // second command must still run.
    val log = TempDirs.dir() / "fmt-log"
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "fail-open format",
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List("false", s"echo ran >> '$log'")),
      initialDiff = Some("")
    )
    assertEquals(os.read.lines(log).toList, List("ran"))

  test(
    "FromSettings + non-empty settings: format commands run in order, lint " +
      "gate built on the lead's cheap tier"
  ):
    // Both parameters omitted (FromSettings). The format commands come from
    // `stackSettings.format` and must run sequentially; the lint gate must be
    // `Lint(stackSettings.lint, ctx.reviewAgent.cheap)` — the review-role FakeAgent's
    // `cheap` is itself, so the lint summary drains the LEAD's scripted
    // output, proving the summariser wiring.
    val fmtLog = TempDirs.dir() / "fmt-log"
    val lead = new FakeAgent(
      name = "lead",
      outputs = List(ReviewResult(List(issue("lint-found", confidence = 0.9))))
    )
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      lead = Some(lead),
      stackSettings = StackSettings(
        format = List(s"echo first >> '$fmtLog'", s"echo second >> '$fmtLog'"),
        lint = List("echo lint-output")
      )
    )
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(Nil, List(IgnoredIssue(Title("lint-found"), "accepted")))
      )
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "settings-driven gates",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(os.read.lines(fmtLog).toList, List("first", "second"))
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("lint-found"), "accepted"))
    )

  test("FromSettings + empty settings: no format, no lint (≡ omission)"):
    // Empty settings ≡ no gate: no `Lint` is built, so the context's lead —
    // a throwing stub here (`lead = None`) — must never be touched, and the
    // loop behaves exactly like today's omission.
    given FlowControl = control
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "empty settings",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("Configured.Off keeps both gates off despite non-empty settings"):
    // Settings define format + lint, but the call opts out. The format
    // command would create `fmtLog` if it ran; resolving lint from settings
    // would touch the throwing stub lead (`lead = None`).
    val fmtLog = TempDirs.dir() / "fmt-log"
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      stackSettings = StackSettings(
        format = List(s"echo x >> '$fmtLog'"),
        lint = List("echo lint-output")
      )
    )
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "explicitly off",
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Off,
      lint = Configured.Off,
      initialDiff = Some("")
    )
    assertEquals(result, IgnoredIssues(Nil))
    assert(!os.exists(fmtLog), "format must not run under Configured.Off")

  test("Configured.Use beats non-empty settings"):
    // Settings define their own format + lint, but explicit `Use` values win:
    // only the explicit format command runs, and the explicit summariser (not
    // the throwing stub lead that FromSettings would resolve) handles lint.
    val fmtLog = TempDirs.dir() / "fmt-log"
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      stackSettings = StackSettings(
        format = List(s"echo settings >> '$fmtLog'"),
        lint = List("echo from-settings")
      )
    )
    val summariser =
      new FakeAgent("summariser", outputs = List(ReviewResult.empty))
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "explicit override",
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List(s"echo explicit >> '$fmtLog'")),
      lint = Configured.Use(Lint(List("echo overridden"), summariser)),
      initialDiff = Some("")
    )
    assertEquals(result, IgnoredIssues(Nil))
    assertEquals(os.read.lines(fmtLog).toList, List("explicit"))

  test("reviewer LLM runs are tagged with the cost role (12.7)"):
    // The loop keeps reviewer identity as the bare slug and tags the LLM run
    // with the `reviewer` role (not a renamed copy) so `CostTracker` can
    // group/subtotal the spend without a stringly identity convention.
    val recorded =
      new java.util.concurrent.ConcurrentLinkedQueue[OrcaEvent.TokensUsed]()
    val listener: OrcaListener =
      case t: OrcaEvent.TokensUsed => recorded.add(t): Unit
      case _                       => ()
    given FlowControl =
      ReviewLoopFixture.control(new EventDispatcher(List(listener)))
    val reviewer = new TokenEmittingReviewer("performance", ReviewResult.empty)
    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = "cost labelling",
      reviewerSelection = ReviewerSelector.allEveryRound,
      initialDiff = Some("")
    )
    val events = recorded.toArray.toList.collect {
      case t: OrcaEvent.TokensUsed =>
        t
    }
    assertEquals(events.map(_.agent), List("performance"))
    assertEquals(events.map(_.role), List(Some("reviewer")))

  test("a selector runs exactly the roster entries it returns"):
    // Roster-bound contract: `prepare` is handed the roster as opaque
    // `RosterEntry` handles and can only return a subset/permutation of them —
    // a foreign agent is unrepresentable. Here the selector keeps only "x", so
    // "y" (an empty-output stub that would throw if run) must never run.
    given FlowControl = control
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issue("from-x", confidence = 0.9))))
    )
    val rosterY = new FakeAgent(name = "y") // no outputs: throws if run
    val onlyX = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage) =
        _ => all.filter(_.name == "x")
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(Nil, List(IgnoredIssue(Title("from-x"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterX, rosterY),
      reviewerSelection = onlyX,
      task = "roster-bound selection",
      initialDiff = Some("")
    )
    assertEquals(result.issues, List(IgnoredIssue(Title("from-x"), "ok")))
    assert(rosterX.seenSessions.nonEmpty, "the selected reviewer must run")
    assert(
      rosterY.seenSessions.isEmpty,
      "an unselected reviewer must not run"
    )

  test("an empty selection runs no reviewers and stops the round honestly"):
    // An empty selection means exactly what it says: no reviewers run this
    // round. With no issues found, the shared stop policy converges — the loop
    // never resurrects the roster behind the selector's back, and the
    // (empty-output) coder is never asked to fix anything.
    given FlowControl = control
    val rosterA = new FakeAgent(name = "a") // no outputs: throws if run
    val emptySelector = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage) =
        _ => Nil
    val coder = new FakeAgent(name = "coder") // throws if a fix turn runs
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterA),
      reviewerSelection = emptySelector,
      task = "empty selection",
      initialDiff = Some("")
    )
    assert(
      rosterA.seenSessions.isEmpty,
      "an unselected reviewer must not run"
    )
    assertEquals(
      result,
      IgnoredIssues(Nil),
      "empty selection ⇒ no issues ⇒ loop stops with nothing accumulated"
    )

  test("a selector returning the same entry twice runs it once that round"):
    // Entries are keyed by identity, so `active.distinct` collapses an
    // accidental duplicate: the reviewer runs a single time (one session, one
    // scripted output — a second concurrent run would race its session mint and
    // drain its empty iterator).
    given FlowControl = control
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issue("from-x", confidence = 0.9))))
    )
    val dupSelector = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, orca.InStage) =
        _ => all ++ all
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(Nil, List(IgnoredIssue(Title("from-x"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterX),
      reviewerSelection = dupSelector,
      task = "duplicate selection",
      initialDiff = Some("")
    )
    assertEquals(rosterX.seenSessions.size, 1)
    assertEquals(result.issues, List(IgnoredIssue(Title("from-x"), "ok")))

  test(
    "fix turn seeds a fresh coder session but not a live one"
  ):
    // The fix turn routes through the durable FlowSession door: on a coder
    // whose backend conversation is fresh/lost it re-applies the recorded seed;
    // on a live one it forwards the fix request verbatim.
    val seed = "SEED-MARKER: you are the fixer for this repo."

    def fixPromptWhen(existsResult: Boolean): String =
      val control = ReviewLoopFixture.control(new EventDispatcher(Nil))
      // Record the coder session's seed under its id ("s", from the fixture).
      control.progressStore.upsertSession(
        SessionRecord(name = "s", occurrence = 0, id = "s", seed = seed)
      )
      given FlowControl = control
      val coder = new SeedProbingCoder(
        existsResult = existsResult,
        fixOutcome = FixOutcome(Nil, List(IgnoredIssue(Title("x"), "ok")))
      )
      val reviewer = new FakeAgent(
        name = "r",
        outputs = List(ReviewResult(List(issue("x", confidence = 0.9))))
      )
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = "seed check",
        reviewerSelection = ReviewerSelector.allEveryRound,
        initialDiff = Some("")
      )
      coder.capturedFixPrompt.getOrElse(fail("the fix turn never ran"))

    val freshPrompt = fixPromptWhen(existsResult = false)
    assert(
      freshPrompt.contains(seed),
      s"a fresh coder session's fix turn must be re-seeded; got: $freshPrompt"
    )
    val livePrompt = fixPromptWhen(existsResult = true)
    assert(
      !livePrompt.contains(seed),
      s"a live coder session's fix turn must NOT be re-seeded; got: $livePrompt"
    )
