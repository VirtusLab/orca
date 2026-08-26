package orca.review

import orca.{Configured, FlowContext, FlowControl, StackSettings}
import orca.plan.{Task, Title}
import orca.agents.{
  AgentInput,
  Announce,
  AutonomousAgentCall,
  BackendTag,
  InteractiveAgentCall,
  JsonData,
  AgentCall,
  AgentConfig,
  Agent,
  SessionId,
  WireSessionId
}
import orca.backend.{IdScheme, SessionSupport}
import orca.progress.SessionRecord
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener, Usage}
import orca.testkit.TempDirs

/** A reviewer stub that emits a `TokensUsed` event carrying the name + role
  * captured at `resultAs` time, mirroring `BaseAgent`. `withRole` returns a
  * role-tagged copy with `name` unchanged.
  */
private class TokenEmittingReviewer(
    name: String,
    result: ReviewResult,
    override val role: Option[String] = None
)(using ctx: FlowContext)
    extends StubAgent(name):
  override def withName(n: String): Agent[BackendTag.ClaudeCode.type] =
    new TokenEmittingReviewer(n, result, role)
  override def withRole(r: String): Agent[BackendTag.ClaudeCode.type] =
    new TokenEmittingReviewer(name, result, Some(r))
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
              sessionName: Option[String],
              c: Option[AgentConfig],
              emitPrompt: Boolean
          )(using orca.InStage): O =
            ctx.emit(
              OrcaEvent
                .TokensUsed(
                  capturedName,
                  None,
                  Usage.empty,
                  capturedRole,
                  cost = None
                )
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
) extends StubAgent("coder"):

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

  def resultAs[O: JsonData: Announce]
      : AgentCall[BackendTag.ClaudeCode.type, O] =
    new AgentCall[BackendTag.ClaudeCode.type, O]:
      val autonomous: AutonomousAgentCall[BackendTag.ClaudeCode.type, O] =
        new AutonomousAgentCall[BackendTag.ClaudeCode.type, O]:
          private[orca] def runWithSession[I: AgentInput](
              input: I,
              session: SessionId[BackendTag.ClaudeCode.type],
              sessionName: Option[String],
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
      task = titled("do the thing"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("every finding a reviewer reports reaches the fixer"):
    // Nothing between the reviewer and the fix turn filters findings, whatever
    // their severity: all three arrive in the one prompt the fixer is sent.
    given FlowControl = control
    val reviewer = new FakeAgent(
      name = "mixed",
      outputs = List(
        ReviewResult(
          List(
            issue("crit-finding", severity = Severity.Critical),
            issue("warn-finding", severity = Severity.Warning),
            issue("info-finding", severity = Severity.Info)
          )
        )
      )
    )
    val coder = new SeedProbingCoder(
      existsResult = true,
      fixOutcome = FixOutcome(Nil, Nil)
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val fixPrompt =
      coder.capturedFixPrompt.getOrElse(fail("the fix turn never ran"))
    assert(fixPrompt.contains("crit-finding"), fixPrompt)
    assert(fixPrompt.contains("warn-finding"), fixPrompt)
    assert(fixPrompt.contains("info-finding"), fixPrompt)

  test("a finding is shown under the key the fixer is handed for it"):
    // The fixer narrates its work by key ("Fix I2.1"), so a key on screen that
    // named a different finding in the prompt would misattribute every fix.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val first = new FakeAgent(
      name = "first",
      outputs = List(ReviewResult(List(issue("a"), issue("b"))))
    )
    val second = new FakeAgent(
      name = "second",
      outputs = List(ReviewResult(List(issue("c"))))
    )
    val coder = new FakeAgent("coder", outputs = List(FixOutcome(Nil, Nil)))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(first, second),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assert(
      steps.messages.exists(_.contains("- I2.1 [Warning] c")),
      steps.messages.mkString("\n")
    )
    assert(
      coder.seenPrompts.head.contains("I2.1 [Warning] c"),
      coder.seenPrompts.mkString("\n")
    )

  test("an exit with nothing left open prints no closing block"):
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(new FakeAgent("quiet", List(ReviewResult.empty))),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assert(
      !emitted.exists(_.startsWith("Unresolved findings")),
      emitted.mkString("\n")
    )

  test("a clean exit still names what the fixer declined in an earlier round"):
    // The reviewer drops the finding once it is told the fixer refused it, so
    // the last round comes back clean while the refusal is still open. The
    // headline must not read as an all-clear.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(List(issue("driver"), issue("nit"))),
        ReviewResult.empty
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("driver")),
          List(IgnoredIssue(Title("nit"), "deliberate"))
        )
      )
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assert(emitted.contains("No issues to fix"), emitted.mkString("\n"))
    assert(
      emitted.contains(
        """Unresolved findings (1):
          |  - [Warning] nit
          |    deliberate""".stripMargin
      ),
      emitted.mkString("\n")
    )

  test("the cap exit names what it leaves open, and why"):
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List.fill(2)(ReviewResult(List(issue("stubborn"))))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(List(Title("stubborn")), Nil))
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      maxIterations = 1,
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assert(emitted.contains("Reached max iterations (1)"), emitted.mkString)
    assert(
      emitted.contains(
        """Unresolved findings (1):
          |  - [Warning] stubborn
          |    max iterations (1) reached""".stripMargin
      ),
      emitted.mkString("\n")
    )

  test("the halt exit names what the fixer refused, and its reason"):
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(
          List(issue("race in the driver", severity = Severity.Critical))
        )
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          Nil,
          List(IgnoredIssue(Title("race in the driver"), "the lock covers it"))
        )
      )
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assertEquals(
      emitted.count(
        _ == """Unresolved findings (1):
               |  - [Critical] race in the driver
               |    the lock covers it""".stripMargin
      ),
      1,
      emitted.mkString("\n")
    )

  test("a finding declined then fixed is neither reported nor re-sent"):
    given FlowControl = control
    // The reviewer re-reports what round one declined and the fixer fixes it,
    // so the decline is stale: it must leave the result, and round three's
    // reviewers must not be told the finding is still declined.
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(
          List(
            issue("nit"),
            issue("driver")
          )
        ),
        ReviewResult(List(issue("nit"))),
        ReviewResult.empty
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("driver")),
          List(IgnoredIssue(Title("nit"), "deliberate"))
        ),
        FixOutcome(List(Title("nit")), Nil)
      )
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(result, IgnoredIssues(Nil))
    val roundThree = reviewer.seenPrompts
      .lift(2)
      .getOrElse(fail(s"expected three review rounds: ${reviewer.seenPrompts}"))
    assert(!roundThree.contains("deliberate"), roundThree)

  test("the fix prompt carries each issue's description"):
    // Reviewers are asked for "a longer description with enough context for a
    // fixer to act"; the display rendering drops it, so the fix prompt has its
    // own.
    given FlowControl = control
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(
          List(
            ReviewIssue(
              severity = Severity.Warning,
              title = Title("leaks a handle"),
              description = "DESCRIPTION-MARKER: the stream is never closed",
              location = None,
              suggestion = None
            )
          )
        )
      )
    )
    val coder = new SeedProbingCoder(
      existsResult = true,
      fixOutcome = FixOutcome(Nil, Nil)
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val fixPrompt =
      coder.capturedFixPrompt.getOrElse(fail("the fix turn never ran"))
    assert(fixPrompt.contains("DESCRIPTION-MARKER"), fixPrompt)

  test("a paraphrased fixer reply records the finding once, not twice"):
    // The echoed title matches no handed issue, so it is dropped from the books
    // and named in a Step, and the finding surfaces once, as unaccounted.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(ReviewResult(List(issue("real bug"))))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(Nil, List(IgnoredIssue(Title("Real bug!"), "not a bug")))
      )
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("real bug"), "fixer reported no fixes"))
    )
    val emitted = steps.messages
    assert(
      emitted.contains(
        "Fixer named Real bug!, which matched no issue it was handed"
      ),
      emitted.mkString("\n")
    )

  test(
    "a finding declined in two rounds is recorded once, with the latest reason"
  ):
    given FlowControl = control
    // The reviewer re-reports what the fixer declined, and the fixer declines
    // it again. That is one finding, so the result carries one entry.
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List.fill(2)(
        ReviewResult(
          List(
            issue("nit"),
            issue("real bug")
          )
        )
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real bug")),
          List(IgnoredIssue(Title("nit"), "deliberate"))
        ),
        FixOutcome(Nil, List(IgnoredIssue(Title("nit"), "still deliberate")))
      )
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(
      result.issues,
      List(
        IgnoredIssue(Title("nit"), "still deliberate"),
        IgnoredIssue(Title("real bug"), "fixer reported no fixes")
      )
    )

  test("a declined finding the last turn forgot is recorded once, not twice"):
    given FlowControl = control
    // Round two's reply accounts for nothing, so "nit" is both an earlier
    // decline and now unaccounted for. Two entries would contradict each other
    // about the same finding; the exit records the latest reason only.
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(
          List(
            issue("nit"),
            issue("real bug")
          )
        ),
        ReviewResult(List(issue("nit")))
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real bug")),
          List(IgnoredIssue(Title("nit"), "deliberate"))
        ),
        FixOutcome(Nil, Nil)
      )
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("nit"), "fixer reported no fixes"))
    )

  test("a reviewer joining in round three sees round one's declines"):
    given FlowControl = control
    // Declines accumulate across rounds, so a late joiner learns what was
    // settled before it started — not merely what the previous round settled.
    val early = new FakeAgent(
      name = "early",
      outputs = List(
        ReviewResult(
          List(issue("a"), issue("b"))
        ),
        ReviewResult(List(issue("c"))),
        ReviewResult.empty
      )
    )
    val late = new FakeAgent("late", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("a")),
          List(IgnoredIssue(Title("b"), "by design"))
        ),
        FixOutcome(List(Title("c")), Nil)
      )
    )
    val joinsInRoundThree = selector: (all, history) =>
      if history.size < 2 then all.filter(_.name == "early") else all
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(early, late),
      task = titled("build the widget"),
      reviewerSelection = joinsInRoundThree,
      diff = ReviewDiff.Pinned("")
    )
    val joined = late.seenPrompts.headOption
      .getOrElse(fail("the late reviewer never ran"))
    assert(joined.contains("- b: by design"), joined)

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
      task = titled("multi"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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
      task = titled("never ending"),
      maxIterations = 2,
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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

  test("a lint summariser that reports nothing is resumed on later rounds"):
    given FlowControl = control
    // A reviewer, not the lint, keeps the loop iterating; the lint runs every
    // round and finds nothing, so its conversation holds no finding to repeat
    // and carries forward.
    val loud = new FakeAgent(
      name = "loud",
      outputs = List.fill(3)(ReviewResult(List(issue("never ends"))))
    )
    val summariser = new FakeAgent(
      name = "summariser",
      outputs = List.fill(3)(ReviewResult.empty)
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List.fill(2)(FixOutcome(List(Title("never ends")), Nil))
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(loud),
      task = titled("warm lint"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      // `echo` emits output so `lint` doesn't short-circuit before calling the
      // summariser.
      lint = Configured.Use(Lint(List("echo lint-output"), summariser)),
      maxIterations = 2,
      diff = ReviewDiff.Pinned("")
    )
    val lintSessions = summariser.seenSessions
    assertEquals(lintSessions.size, 3)
    assertEquals(
      lintSessions.distinct.size,
      1,
      s"a silent lint summariser must reuse one session; got ${lintSessions
          .map(SessionId.value)}"
    )

  test("a lint summariser that reports findings is not resumed"):
    given FlowControl = control
    // The stale-findings guard: once the summariser has reported, its
    // conversation could repeat those findings on a later round whose commands
    // no longer show them, so the next round starts a fresh one.
    val quiet =
      new FakeAgent(name = "quiet", outputs = List.fill(2)(ReviewResult.empty))
    val summariser = new FakeAgent(
      name = "summariser",
      outputs = List.fill(2)(ReviewResult(List(issue("lint-found"))))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(List(Title("lint-found")), Nil))
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(quiet),
      task = titled("reporting lint"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      lint = Configured.Use(Lint(List("echo lint-output"), summariser)),
      maxIterations = 1,
      diff = ReviewDiff.Pinned("")
    )
    val lintSessions = summariser.seenSessions
    assertEquals(lintSessions.size, 2)
    assertEquals(
      lintSessions.distinct.size,
      2,
      s"a reporting lint summariser must not be resumed; got ${lintSessions
          .map(SessionId.value)}"
    )

  test("a pinned diff is embedded in the reviewer's first prompt"):
    given FlowControl = control
    val captureReviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(captureReviewer),
      task = titled("do thing"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("--- a/Foo.scala\n+++ b/Foo.scala\n+ added line")
    )
    val sent = captureReviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(sent.contains("--- a/Foo.scala"), s"diff missing from prompt: $sent")
    assert(sent.contains("do thing"), s"task missing from prompt: $sent")

  test("the reviewer's first prompt carries the task and the user's request"):
    // A reviewer given only the title argues with the fixer over choices the
    // planner settled in the description — which the fixer can see and it
    // cannot.
    given FlowControl =
      ReviewLoopFixture.control(
        new EventDispatcher(Nil),
        userPrompt = "add a median function"
      )
    val reviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = Task(Title("Median"), "on an even count, average the two middle"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val sent = reviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(
      sent.contains("on an even count, average the two middle"),
      s"task description missing from prompt: $sent"
    )
    assert(
      sent.contains("add a median function"),
      s"user request missing from prompt: $sent"
    )

  test("the reviewer's first prompt carries a caller-supplied user request"):
    // The override exists for a flow whose prompt is only an issue reference.
    given FlowControl =
      ReviewLoopFixture.control(
        new EventDispatcher(Nil),
        userPrompt = "acme/widgets#42"
      )
    val reviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("Median"),
      userRequest = Some("the median rounds down on ties"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val sent = reviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(
      sent.contains("the median rounds down on ties"),
      s"caller's user request missing from prompt: $sent"
    )
    assert(
      !sent.contains("acme/widgets#42"),
      s"the run prompt reached the reviewer instead: $sent"
    )

  test("the reviewer's first prompt names the stage's base commit"):
    // The base is sent alongside the diff, from the same `stageBaseCommit` the
    // diff is sampled against — not a second notion of "base".
    val fc = ReviewLoopFixture.control(new EventDispatcher(Nil))
    given FlowControl = fc
    val base =
      fc.git.headCommit().getOrElse(fail("the fixture repo has no HEAD"))
    val _ = fc.enterStage("review", Some(base))
    val reviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("do thing"),
      reviewerSelection = ReviewerSelector.allEveryRound
    )
    val sent = reviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(sent.contains(s"since commit $base"), s"base missing: $sent")

  test("a pinned diff is framed without the stage's base commit or scope"):
    // The pinned diff may describe a change set that isn't stage-base-to-tree,
    // so neither naming that commit as its base nor the sampled path's "since
    // its stage began" framing can be claimed: both send the reviewer to the
    // wrong history.
    val fc = ReviewLoopFixture.control(new EventDispatcher(Nil))
    given FlowControl = fc
    val base =
      fc.git.headCommit().getOrElse(fail("the fixture repo has no HEAD"))
    val _ = fc.enterStage("review", Some(base))
    val reviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("do thing"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("--- a/Foo.scala\n+++ b/Foo.scala\n+ added line")
    )
    val sent = reviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(!sent.contains("since commit"), s"base leaked into prompt: $sent")
    assert(
      !sent.contains("since its stage began"),
      s"stage-scoped framing leaked into prompt: $sent"
    )

  test("a sampled diff is framed as everything the stage has changed"):
    // The framing the pinned path can't claim, on the path that can — a
    // reviewer that reads it as "since the last commit" would skip committed
    // work.
    val fc = ReviewLoopFixture.control(new EventDispatcher(Nil))
    given FlowControl = fc
    val base =
      fc.git.headCommit().getOrElse(fail("the fixture repo has no HEAD"))
    val _ = fc.enterStage("review", Some(base))
    val reviewer =
      new FakeAgent("capturing", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("do thing"),
      reviewerSelection = ReviewerSelector.allEveryRound
    )
    val sent = reviewer.seenPrompts.headOption
      .getOrElse(fail("the fresh-session run was never called"))
    assert(sent.contains("since its stage began"), s"framing missing: $sent")

  test("the fixer's declines reach the next round's reviewer, its fixes don't"):
    // A decline is the one thing a reviewer cannot recover by reading the tree:
    // nothing in the code says a finding was considered and refused. A "fixed"
    // title is the opposite — it answers the question the round exists to ask,
    // so it is deliberately withheld.
    given FlowControl = control
    val reviewer = new FakeAgent(
      name = "loud",
      outputs = List(
        ReviewResult(List(issue("real bug"), issue("nit"))),
        ReviewResult.empty
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real bug")),
          List(IgnoredIssue(Title("nit"), "the shape is deliberate"))
        )
      )
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("build the widget"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    val resumed = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer was never resumed"))
    assert(
      resumed.contains("- nit: the shape is deliberate"),
      s"decline missing from re-review prompt: $resumed"
    )
    assert(
      !resumed.contains("real bug"),
      s"fixed title leaked into re-review prompt: $resumed"
    )

  test(
    "an agentDriven reviewerSelection narrows the active set via its picker LLM"
  ):
    given FlowControl = control
    val issueX = issue("only-x")
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
      task = titled("picker-routing check"),
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("only-x"), "accepted"))
    )

  test(
    "omitting reviewerSelection defaults to a picker on the lead's cheap tier"
  ):
    // With no reviewerSelection the default (`ReviewerSelector.default`)
    // resolves its picker as the review role's cheap tier (`ctx.reviewAgent.cheap`).
    // The control wires the coder as the lead, and FakeAgent.cheap == this, so
    // the default picker draws the reviewer pick from the coder's outputs
    // (then the fix) — proving selection routed through the context's lead.
    // "y" (empty outputs) would throw if the picker failed to narrow it out.
    val issueX = issue("only-x")
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
      task = titled("default selection"),
      diff = ReviewDiff.Pinned("")
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

  test("the default selection drops a reviewer that reported nothing"):
    // Three evaluation rounds (two fix attempts, then the cap). "quiet" reports
    // nothing in round one, so the default narrowing drops it: it must open
    // exactly one session. Its outputs cover all three rounds, so a regression
    // is reported by the assertion rather than by an exhausted iterator inside
    // the fan-out. "loud" keeps reporting, which keeps it — and the loop — in.
    val quiet =
      new FakeAgent(name = "quiet", outputs = List.fill(3)(ReviewResult.empty))
    val loud = new FakeAgent(
      name = "loud",
      outputs = List.fill(3)(ReviewResult(List(issue("stubborn"))))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        SelectedReviewers(List("quiet", "loud")),
        FixOutcome(List(Title("stubborn")), Nil),
        FixOutcome(List(Title("stubborn")), Nil)
      )
    )
    given FlowControl =
      ReviewLoopFixture.control(new EventDispatcher(Nil), lead = Some(coder))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(quiet, loud),
      task = titled("narrowing check"),
      maxIterations = 2,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(quiet.seenSessions.size, 1)
    assertEquals(loud.seenSessions.size, 3)

  test("a lint-only round still leaves the next round with reviewers"):
    // Reviewer silence alone ends the loop, but a lint gate keeps it iterating
    // through it — and the fixer keeps editing. Narrowing must not strand those
    // rounds with zero reviewers, so the picked set comes back instead.
    val quiet =
      new FakeAgent(name = "quiet", outputs = List.fill(3)(ReviewResult.empty))
    val summariser = new FakeAgent(
      name = "summariser",
      outputs = List.fill(3)(ReviewResult(List(issue("lint-found"))))
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        SelectedReviewers(List("quiet")),
        FixOutcome(List(Title("lint-found")), Nil),
        FixOutcome(List(Title("lint-found")), Nil)
      )
    )
    given FlowControl =
      ReviewLoopFixture.control(new EventDispatcher(Nil), lead = Some(coder))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(quiet),
      task = titled("lint keeps the loop going"),
      // `echo` emits output so `lint` doesn't short-circuit before calling the
      // summariser.
      lint = Configured.Use(Lint(List("echo lint-output"), summariser)),
      maxIterations = 2,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(quiet.seenSessions.size, 3)

  test(
    "explicit allEveryRound reviewerSelection skips the LLM picker entirely"
  ):
    given FlowControl = control
    val issueX = issue("only-x")
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
      task = titled("no-picker check"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("only-x"), "accepted"))
    )

  test("the round's opening Step names every agent it runs"):
    // The lint gate is one of the named agents, not a silent extra.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val summariser =
      new FakeAgent(name = "summariser", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession =
        ReviewLoopFixture.coderSession(new FakeAgent(name = "coder")),
      reviewers = List(
        new FakeAgent(name = "a", outputs = List(ReviewResult.empty)),
        new FakeAgent(name = "b", outputs = List(ReviewResult.empty))
      ),
      task = titled("named agents"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      // `echo` emits output so `lint` doesn't short-circuit before calling the
      // summariser.
      lint = Configured.Use(Lint(List("echo lint-output"), summariser)),
      diff = ReviewDiff.Pinned("")
    )
    assert(
      steps.messages.contains("Running 3 review agents: a, b, lint"),
      steps.messages.mkString("\n")
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

    def gatedReviewer(
        label: String,
        gate: java.util.concurrent.CountDownLatch
    ): FakeAgent =
      new FakeAgent(
        name = label,
        outputs = List(ReviewResult.empty),
        onRun = () =>
          val ok = gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
          assert(ok, s"$label gate never opened")
      )

    val slow = gatedReviewer("slow", gate1)
    val fast = gatedReviewer("fast", gate2)
    val runner = new Thread(() =>
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
        reviewers = List(slow, fast),
        task = titled("ordering check"),
        reviewerSelection = ReviewerSelector.allEveryRound,
        diff = ReviewDiff.Pinned("")
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

    def rendezvousReviewer(label: String): FakeAgent =
      new FakeAgent(
        name = label,
        outputs = List(ReviewResult.empty),
        onRun = () =>
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
      )

    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(rendezvousReviewer("reviewer")),
      task = titled("concurrency check"),
      // echo emits output so `lint` doesn't short-circuit on empty stdout
      // and actually calls the (rendezvousing) LLM summariser.
      lint = Configured.Use(
        Lint(List("echo lint-output"), rendezvousReviewer("lint"))
      ),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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
      task = titled("format check"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List(s"echo x >> '$counter'")),
      diff = ReviewDiff.Pinned("")
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
      task = titled("fail-open format"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List("false", s"echo ran >> '$log'")),
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(os.read.lines(log).toList, List("ran"))

  test("a failing format command is named in a Step, with its exit code"):
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("reported format failure"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List("exit 3")),
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assert(
      emitted.contains("format command failed (exit 3): exit 3"),
      emitted.mkString("\n")
    )

  test("a format command that succeeds says nothing"):
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(reviewer),
      task = titled("silent format success"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List("true")),
      diff = ReviewDiff.Pinned("")
    )
    val emitted = steps.messages
    assert(
      !emitted.exists(_.startsWith("format command failed")),
      emitted.mkString("\n")
    )

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
      outputs = List(ReviewResult(List(issue("lint-found"))))
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
      task = titled("settings-driven gates"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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
      task = titled("empty settings"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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
      task = titled("explicitly off"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Off,
      lint = Configured.Off,
      diff = ReviewDiff.Pinned("")
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
      task = titled("explicit override"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List(s"echo explicit >> '$fmtLog'")),
      lint = Configured.Use(Lint(List("echo overridden"), summariser)),
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(result, IgnoredIssues(Nil))
    assertEquals(os.read.lines(fmtLog).toList, List("explicit"))

  test("reviewer LLM runs are tagged with the cost role"):
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
      task = titled("cost labelling"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      diff = ReviewDiff.Pinned("")
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
      outputs = List(ReviewResult(List(issue("from-x"))))
    )
    val rosterY = new FakeAgent(name = "y") // no outputs: throws if run
    val onlyX = selector((all, _) => all.filter(_.name == "x"))
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(Nil, List(IgnoredIssue(Title("from-x"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterX, rosterY),
      reviewerSelection = onlyX,
      task = titled("roster-bound selection"),
      diff = ReviewDiff.Pinned("")
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
    // (empty-output) coder is never asked to fix anything. The round says so,
    // since converging on nothing is otherwise indistinguishable from a clean
    // review.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = ReviewLoopFixture.control(steps.dispatcher)
    val rosterA = new FakeAgent(name = "a") // no outputs: throws if run
    val emptySelector = selector((_, _) => Nil)
    val coder = new FakeAgent(name = "coder") // throws if a fix turn runs
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterA),
      reviewerSelection = emptySelector,
      task = titled("empty selection"),
      diff = ReviewDiff.Pinned("")
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
    val emitted = steps.messages
    assert(
      emitted.contains("reviewer selection returned no reviewers this round"),
      emitted.mkString("\n")
    )

  test("a reviewer joining a later round is told what the fixer declined"):
    // The declines are the one thing a reviewer cannot recover by reading the
    // code, and a late joiner's prompt is the initial one, not a resume.
    given FlowControl = control
    val early = new FakeAgent(
      name = "early",
      outputs = List(
        ReviewResult(List(issue("real bug"), issue("nit"))),
        ReviewResult.empty
      )
    )
    val late = new FakeAgent("late", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real bug")),
          List(IgnoredIssue(Title("nit"), "the shape is deliberate"))
        )
      )
    )
    val lateJoiner = selector: (all, history) =>
      if history.isEmpty then all.filter(_.name == "early") else all
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(early, late),
      task = titled("build the widget"),
      reviewerSelection = lateJoiner,
      diff = ReviewDiff.Pinned("")
    )
    val joined = late.seenPrompts.headOption
      .getOrElse(fail("the late reviewer never ran"))
    assert(joined.contains("- nit: the shape is deliberate"), joined)

  test("a selector returning the same entry twice runs it once that round"):
    // Entries carry a `ReviewerId`, so `active.distinctBy(_.id)` collapses an
    // accidental duplicate: the reviewer runs a single time (one session, one
    // scripted output — a second concurrent run would race its session mint and
    // drain its empty iterator).
    given FlowControl = control
    val rosterX = new FakeAgent(
      name = "x",
      outputs = List(ReviewResult(List(issue("from-x"))))
    )
    val dupSelector = selector((all, _) => all ++ all)
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(FixOutcome(Nil, List(IgnoredIssue(Title("from-x"), "ok"))))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(rosterX),
      reviewerSelection = dupSelector,
      task = titled("duplicate selection"),
      diff = ReviewDiff.Pinned("")
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
        outputs = List(ReviewResult(List(issue("x"))))
      )
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("seed check"),
        reviewerSelection = ReviewerSelector.allEveryRound,
        diff = ReviewDiff.Pinned("")
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
