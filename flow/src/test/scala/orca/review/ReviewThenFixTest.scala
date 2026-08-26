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
    ReviewLoopFixture.control(dispatcher, lead = Some(picker))

  private def picking(names: String*): FakeAgent =
    new FakeAgent("picker", outputs = List(SelectedReviewers(names.toList)))

  test("a finding gets one fix turn and no second review"):
    // Both stubs are scripted for exactly one call, so a second review round or
    // a second fix turn exhausts an iterator and throws.
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(issue("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing")
    )
    assertEquals(result, IgnoredIssues(Nil))
    assertEquals(reviewer.seenSessions.size, 1)
    assertEquals(coder.seenSessions.size, 1)

  test("a clean review runs no fix turn"):
    // The coder has no scripted outputs: a fix turn would throw.
    val reviewer = new FakeAgent("quiet", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent("coder")
    given FlowControl = control(picking("quiet"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing")
    )
    assertEquals(result, IgnoredIssues(Nil))
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
        ReviewResult(List(issue("real"), issue("nit"), issue("forgotten")))
      )
    )
    val coder = new FakeAgent(
      name = "coder",
      outputs = List(
        FixOutcome(
          List(Title("real")),
          List(IgnoredIssue(Title("nit"), "deliberate"))
        )
      )
    )
    given FlowControl = control(picking("x"))
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing")
    )
    assertEquals(
      result.issues,
      List(
        IgnoredIssue(Title("nit"), "deliberate"),
        IgnoredIssue(Title("forgotten"), "fixer did not report on it")
      )
    )

  test("a fixer that fixes nothing halts the pass with the finding recorded"):
    // The pass ends here either way; what changes is the reason the finding
    // carries, and it is the only record of it left.
    val steps = new ReviewLoopFixture.StepCapture
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(issue("a")))))
    val coder = new FakeAgent("coder", outputs = List(FixOutcome(Nil, Nil)))
    given FlowControl = control(picking("x"), steps.dispatcher)
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing")
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("a"), "fixer reported no fixes"))
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
      new FakeAgent("x", outputs = List(ReviewResult(List(issue("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"), steps.dispatcher)
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing")
    )
    assert(
      steps.messages.contains("Fixed 1, ignored 0"),
      steps.messages.mkString("\n")
    )
    assert(
      steps.messages.contains("Fixes applied; not reviewing again"),
      steps.messages.mkString("\n")
    )

  test("the fixer's edits are formatted before the pass returns"):
    // The formatter appends one line per run: once before the review, once
    // after the fix turn. No round follows to format those edits, and the
    // enclosing stage commits them.
    val counter = TempDirs.dir() / "fmt-count"
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(issue("a")))))
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    given FlowControl = control(picking("x"))
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer),
      task = titled("do the thing"),
      formatCommands = Configured.Use(List(s"echo x >> '$counter'"))
    )
    assertEquals(os.read.lines(counter).size, 2)

  test("the reviewer pick is made once"):
    // The picker is scripted for one call; a second would throw. `y` has no
    // outputs, so it must stay unpicked.
    val reviewer =
      new FakeAgent("x", outputs = List(ReviewResult(List(issue("a")))))
    val unpicked = new FakeAgent("y")
    val coder =
      new FakeAgent("coder", outputs = List(FixOutcome(List(Title("a")), Nil)))
    val picker = picking("x")
    given FlowControl = control(picker)
    val _ = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(reviewer, unpicked),
      task = titled("do the thing")
    )
    assertEquals(picker.seenSessions.size, 1)
