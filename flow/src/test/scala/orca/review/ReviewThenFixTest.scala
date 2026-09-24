package orca.review

import orca.{Configured, FlowControl}
import orca.plan.Title
import orca.events.EventDispatcher
import orca.testkit.TempDirs

class ReviewThenFixTest extends munit.FunSuite:

  // `reviewThenFix` is gated on `InStage` + `WorkspaceWrite` (ADR 0018 §6);
  // mint both for the suite.
  private given orca.InStage = orca.InStage.unsafe
  private given orca.WorkspaceWrite = orca.WorkspaceWrite.unsafe

  /** A control whose lead — and so `ctx.reviewAgent.cheap`, the picker
    * `ReviewerSelector.agentDriven` resolves — is `picker`.
    */
  private def control(
      picker: FakeAgent,
      dispatcher: EventDispatcher = new EventDispatcher(Nil)
  ): FlowControl =
    ReviewLoopFixture.control(dispatcher, lead = Some(picker.agent))

  private def picking(names: String*): FakeAgent =
    new FakeAgent("picker", outputs = List(SelectedReviewers(names.toList)))

  test("a finding gets one fix turn and no second review"):
    // Both stubs are scripted for exactly one call, so a second review round or
    // a second fix turn exhausts an iterator and throws.
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assertEquals(result, OpenFindings.empty)
    assertEquals(reviewer.seenSessions.size, 1)
    assertEquals(coder.seenSessions.size, 1)

  test("a clean review runs no fix turn"):
    // The coder has no scripted outputs: a fix turn would throw.
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    given FlowControl = control(picking("quiet"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assertEquals(result, OpenFindings.empty)
    assert(
      coder.seenSessions.isEmpty,
      "the fixer must not run on a clean review"
    )

  test(
    "declines and titles the fixer never reported on come back with reasons"
  ):
    // Nothing re-reviews the fix turn, so the returned value is the only record
    // of what stayed open — including the title the fixer said nothing about.
    val reviewer = new FakeAgent(
      name = "x",
      outputs = List(
        ReviewResult(
          List(finding("real"), finding("nit"), finding("forgotten"))
        )
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real")),
          List(DeclinedFinding(Title("nit"), "deliberate"))
        )
      )
    )
    given FlowControl = control(picking("x"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assertEquals(
      result.findings,
      List(
        OpenFinding(
          FindingId("R1.I1.2"),
          Title("nit"),
          OpenReason.Declined("deliberate"),
          None
        ),
        OpenFinding(
          FindingId("R1.I1.3"),
          Title("forgotten"),
          OpenReason.Unaccounted,
          None
        )
      )
    )

  test("a fixer that fixes nothing halts the pass with the finding recorded"):
    // The pass ends here either way; what changes is the reason the finding
    // carries, and it is the only record of it left.
    val steps = new ReviewLoopFixture.StepCapture
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val coder = new FakeAgent("coder", outputs = List(FixOutcome(Nil, Nil)))
    given FlowControl = control(picking("x"), steps.dispatcher)
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assertEquals(
      result.findings,
      List(
        OpenFinding(FindingId("R1.I1.1"), Title("a"), OpenReason.NoFixes, None)
      )
    )
    assert(
      steps.messages.contains("Fixer reported no fixes; ending review"),
      steps.messages.mkString("\n")
    )

  test("the announcement promises no review of the fixes, because none comes"):
    // The single pass returns straight after the fix turn, so a line saying
    // another round follows would be false.
    val steps = new ReviewLoopFixture.StepCapture
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"), steps.dispatcher)
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assert(
      steps.messages.contains("Fixed 1, declined 0"),
      steps.messages.mkString("\n")
    )
    assert(
      steps.messages.contains("Fixes applied; not reviewing again"),
      steps.messages.mkString("\n")
    )

  test("a single pass does not number its round"):
    val steps = new ReviewLoopFixture.StepCapture
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"), steps.dispatcher)
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing")
    )
    assert(
      !steps.messages.exists(_.startsWith("Round ")),
      steps.messages.mkString("\n")
    )

  test("the fixer's edits are formatted before the pass returns"):
    // The formatter appends one line per run: once before the review, once
    // after the fix turn. No round follows to format those edits, and the
    // enclosing stage commits them.
    val counter = TempDirs.dir() / "fmt-count"
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"))
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing"),
      formatCommands = Configured.Use(List(s"echo x >> '$counter'"))
    )
    assertEquals(os.read.lines(counter).size, 2)

  test("a fix that leaves lint failing gets one lint-scoped fix turn"):
    // The lint gate is machine-checkable, so the single pass re-runs it over
    // the fixer's edits and re-drives it once — reviewer findings alone stay
    // single-pass.
    val steps = new ReviewLoopFixture.StepCapture
    val fc =
      ReviewLoopFixture.control(
        steps.dispatcher,
        lead = Some(picking("x").agent)
      )
    given FlowControl = fc
    val flag = fc.context.workDir / "lint-passes"
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    // Scripted for the two calls that reach the summariser — round one and the
    // post-fix re-check; the last check finds the flag and calls no LLM.
    val lintAgent = new FakeAgent(
      "lint-summariser",
      outputs =
        List(ReviewResult.empty, ReviewResult(List(finding("lint broke"))))
    )
    val fixes = new java.util.concurrent.atomic.AtomicInteger(0)
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(List(Title("a")), Nil),
        FixOutcome(List(Title("lint broke")), Nil)
      ),
      // Only the second, lint-scoped turn repairs the gate.
      onRun = () => if fixes.incrementAndGet() == 2 then os.write(flag, "")
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing"),
      lint = Configured.Use(Lint(List(s"test -f '$flag'"), lintAgent.agent))
    )
    assertEquals(result, OpenFindings.empty)
    assertEquals(coder.seenSessions.size, 2)
    assert(
      !steps.messages.exists(_.startsWith("warning: still failing")),
      steps.messages.mkString("\n")
    )

  test("lint still failing after its fix turn is surfaced, not looped"):
    // One re-drive is the whole budget: what still fails lands in the record
    // under a warning, and no third fix turn runs.
    val steps = new ReviewLoopFixture.StepCapture
    val fc =
      ReviewLoopFixture.control(
        steps.dispatcher,
        lead = Some(picking("x").agent)
      )
    given FlowControl = fc
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    // Round one, the post-fix re-check, and the check after the lint-scoped
    // turn all reach the summariser: `false` fails silently every time.
    val lintAgent = new FakeAgent(
      "lint-summariser",
      outputs = List(
        ReviewResult.empty,
        ReviewResult(List(finding("lint broke"))),
        ReviewResult(List(finding("lint broke")))
      )
    )
    // Two scripted turns: a third would throw.
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(List(Title("a")), Nil),
        FixOutcome(List(Title("lint broke")), Nil)
      )
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing"),
      lint = Configured.Use(Lint(List("false"), lintAgent.agent))
    )
    assertEquals(
      result.findings,
      List(
        OpenFinding(
          FindingId("R2.I1.1"),
          Title("lint broke"),
          OpenReason.StillFailing("lint"),
          None
        )
      )
    )
    assertEquals(coder.seenSessions.size, 2)
    assert(
      steps.messages.exists(
        _.startsWith("warning: still failing after the fix turn: lint —")
      ),
      steps.messages.mkString("\n")
    )

  test("a declined lint finding that still fails is recorded once"):
    // The re-check reports the same lint finding the fixer declined; it is one
    // finding, now open because lint still fails.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = control(picking("x"), steps.dispatcher)
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val lintBroke = ReviewResult(List(finding("lint broke")))
    val lintAgent = new FakeAgent(
      "lint-summariser",
      outputs = List(lintBroke, lintBroke, lintBroke)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(
          List(Title("a")),
          List(DeclinedFinding(Title("lint broke"), "not mine"))
        ),
        FixOutcome(Nil, Nil)
      )
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing"),
      lint = Configured.Use(Lint(List("false"), lintAgent.agent))
    )
    assertEquals(
      result.findings,
      List(
        OpenFinding(
          FindingId("R1.I2.1"),
          Title("lint broke"),
          OpenReason.StillFailing("lint"),
          None
        )
      )
    )

  test("a lint failure that survives its fix turn points at its location"):
    // The re-lint runs after the round, so its findings are the ones the
    // round's own location index cannot hold.
    val steps = new ReviewLoopFixture.StepCapture
    given FlowControl = control(picking("x"), steps.dispatcher)
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val lintBroke = ReviewFinding(
      title = Title("lint broke"),
      description = "lint broke",
      location = Some(Location("src/main/Foo.scala", Some(7))),
      suggestion = None,
      reopens = None
    )
    val lintAgent = new FakeAgent(
      "lint-summariser",
      outputs = List(
        ReviewResult.empty,
        ReviewResult(List(lintBroke)),
        ReviewResult(List(lintBroke))
      )
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(List(Title("a")), Nil),
        FixOutcome(List(Title("lint broke")), Nil)
      )
    )
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("do the thing"),
      lint = Configured.Use(Lint(List("false"), lintAgent.agent))
    )
    assert(
      steps.messages.contains(
        s"""Findings still open (1):
           |  - lint broke
           |    at src/main/Foo.scala:7
           |    ${OpenReason.StillFailing("lint").describe}""".stripMargin
      ),
      steps.messages.mkString("\n")
    )

  test("the reviewer pick is made once"):
    // The picker is scripted for one call; a second would throw. `y` has no
    // outputs, so it must stay unpicked.
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(finding("a")))))
    val unpicked = new FakeAgent("y")
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    val picker = picking("x")
    given FlowControl = control(picker)
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer), asReviewer(unpicked)),
      task = titled("do the thing")
    )
    assertEquals(picker.seenSessions.size, 1)
