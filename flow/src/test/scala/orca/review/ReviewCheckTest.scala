package orca.review

import orca.{Configured, FlowContext, FlowControl, InStage}
import orca.plan.Title
import orca.events.EventDispatcher
import orca.testkit.TempDirs

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

/** A check answering with `results` in order; a run past the end fails, which
  * is how a test pins that the check must not run again. `onRun` fires before
  * each answer.
  */
private class ScriptedCheck(
    val name: String,
    results: List[ReviewResult],
    onRun: () => Unit = () => ()
) extends ReviewCheck:
  private val remaining = results.iterator

  def evaluate()(using FlowContext, InStage): ReviewResult =
    onRun()
    if remaining.hasNext then remaining.next()
    else throw new AssertionError(s"$name: no result scripted")

  def exhausted: Boolean = !remaining.hasNext

class ReviewCheckTest extends munit.FunSuite:

  private given InStage = InStage.unsafe
  private given orca.WorkspaceWrite = orca.WorkspaceWrite.unsafe

  test("a loop with only a check converges once the check comes back clean"):
    given FlowControl = ReviewLoopFixture.control(new EventDispatcher(Nil))
    val check = new ScriptedCheck(
      "bench",
      List(ReviewResult(List(finding("too slow"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("too slow")), Nil))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = Nil,
      task = titled("speed it up"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Off,
      lint = Configured.Off,
      checks = List(check)
    )
    assertEquals(result, OpenFindings.empty)
    assert(check.exhausted, "the check must run again after the fix")

  test("checks run after formatting and before the reviewers"):
    given FlowControl = ReviewLoopFixture.control(new EventDispatcher(Nil))
    val formatted = TempDirs.dir() / "formatted"
    val order = new ConcurrentLinkedQueue[String]()
    val check = new ScriptedCheck(
      "bench",
      List(ReviewResult.empty),
      onRun = () =>
        order.add(if os.exists(formatted) then "check" else "unformatted"): Unit
    )
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult.empty),
      onRun = () => order.add("reviewer"): Unit
    )
    val _ = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
      reviewers = List(asReviewer(reviewer)),
      task = titled("t"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Use(List(s"touch '$formatted'")),
      lint = Configured.Off,
      checks = List(check),
      diff = ReviewDiff.Pinned("")
    )
    assertEquals(order.asScala.toList, List("check", "reviewer"))

  test("a reviewer, the lint gate and a check reporting together get own keys"):
    // Each finding is named by its key in the fix turn; a shared key would make
    // the fixer's echo resolve to the wrong finding.
    given FlowControl = ReviewLoopFixture.control(new EventDispatcher(Nil))
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(finding("rev"))), ReviewResult.empty)
    )
    val summariser = new FakeAgent(
      "lint",
      outputs = List(ReviewResult(List(finding("lnt"))), ReviewResult.empty)
    )
    val check = new ScriptedCheck(
      "bench",
      List(ReviewResult(List(finding("chk"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs =
        List(FixOutcome(List(Title("I1.1"), Title("I2.1"), Title("I3.1")), Nil))
    )
    val result = reviewAndFixLoop(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = List(asReviewer(reviewer)),
      task = titled("t"),
      reviewerSelection = ReviewerSelector.allEveryRound,
      formatCommands = Configured.Off,
      lint = Configured.Use(Lint(List("echo lint-output"), summariser.agent)),
      checks = List(check),
      diff = ReviewDiff.Pinned("")
    )
    val fixPrompt =
      coder.seenPrompts.headOption.getOrElse(fail("the fix turn never ran"))
    List("I1.1 rev", "I2.1 lnt", "I3.1 chk").foreach: keyed =>
      assert(fixPrompt.contains(keyed), s"missing '$keyed' in: $fixPrompt")
    assertEquals(result, OpenFindings.empty)

  test("reviewThenFix re-runs a check after its fix turn"):
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      lead = Some(new FakeAgent("picker").agent)
    )
    val check = new ScriptedCheck(
      "bench",
      List(ReviewResult(List(finding("too slow"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("too slow")), Nil))
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = Nil,
      task = titled("speed it up"),
      formatCommands = Configured.Off,
      lint = Configured.Off,
      checks = List(check)
    )
    assertEquals(result, OpenFindings.empty)
    assert(check.exhausted, "the check must run again after the fix")

  test("reviewThenFix records which source still fails after its fix turn"):
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      lead = Some(new FakeAgent("picker").agent)
    )
    val slow = ReviewResult(List(finding("too slow")))
    val broke = ReviewResult(List(finding("lint broke")))
    val check = new ScriptedCheck("bench", List(slow, slow, slow))
    // `false` fails every time, so each run reaches the summariser.
    val summariser =
      new FakeAgent("lint", outputs = List(ReviewResult.empty, broke, broke))
    // Two scripted turns: a third would throw.
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(List(Title("too slow")), Nil),
        FixOutcome(List(Title("too slow"), Title("lint broke")), Nil)
      )
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = Nil,
      task = titled("speed it up"),
      formatCommands = Configured.Off,
      lint = Configured.Use(Lint(List("false"), summariser.agent)),
      checks = List(check)
    )
    assertEquals(
      result.findings.map(f => (f.id, f.reason)),
      List(
        FindingId("R2.I2.1") -> OpenReason.StillFailing(List("bench")),
        FindingId("R2.I1.1") -> OpenReason.StillFailing(List("lint"))
      )
    )
    assert(check.exhausted, "the check must run in the round and twice after")

  test("one defect still failing in lint and a check is one entry naming both"):
    given FlowControl = ReviewLoopFixture.control(
      new EventDispatcher(Nil),
      lead = Some(new FakeAgent("picker").agent)
    )
    val broke = ReviewResult(List(finding("broke")))
    val check = new ScriptedCheck("bench", List(broke, broke, broke))
    val summariser =
      new FakeAgent("lint", outputs = List(ReviewResult.empty, broke, broke))
    val coder = new FakeAgent(
      "coder",
      outputs = List(
        FixOutcome(List(Title("broke")), Nil),
        FixOutcome(List(Title("broke")), Nil)
      )
    )
    val result = reviewThenFix(
      coderSession = ReviewLoopFixture.coderSession(coder),
      reviewers = Nil,
      task = titled("t"),
      formatCommands = Configured.Off,
      lint = Configured.Use(Lint(List("false"), summariser.agent)),
      checks = List(check)
    )
    assertEquals(
      result.findings.map(_.reason),
      List(OpenReason.StillFailing(List("bench", "lint")))
    )
