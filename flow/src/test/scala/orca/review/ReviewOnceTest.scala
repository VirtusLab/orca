package orca.review

import orca.{OrcaFlowException, TestRun}
import orca.events.EventDispatcher
import orca.plan.{Task, Title}

class ReviewOnceTest extends munit.FunSuite:
  private given orca.InStage = orca.InStage.unsafe

  private def freshRun: TestRun =
    ReviewLoopFixture.run(new EventDispatcher(Nil))

  private val task = Task(Title("PR o/r#1: add x"), "")

  private val diff =
    ReviewDiff.InFile(".orca/review.diff", List("a.scala"))

  test("every selected reviewer's findings come back attributed to it"):
    val run = freshRun
    import run.given
    val a = new FakeAgent("alpha", List(ReviewResult(List(finding("A1")))))
    val b = new FakeAgent("beta", List(ReviewResult(Nil)))
    val report = reviewOnce(
      List(asReviewer(a), asReviewer(b)),
      task,
      diff,
      reviewerSelection = ReviewerSelector.allEveryRound
    )
    assertEquals(
      report.byReviewer.sortBy(_.reviewer),
      List(
        ReviewerFindings("alpha", List(finding("A1"))),
        ReviewerFindings("beta", Nil)
      )
    )

  test("each reviewer's findings are shown keyed by its pick position"):
    val steps = new ReviewLoopFixture.StepCapture
    val run = ReviewLoopFixture.run(steps.dispatcher)
    import run.given
    val a = new FakeAgent("alpha", List(ReviewResult(List(finding("A1")))))
    val b = new FakeAgent("beta", List(ReviewResult(List(finding("B1")))))
    val _ = reviewOnce(
      List(asReviewer(a), asReviewer(b)),
      task,
      diff,
      reviewerSelection = ReviewerSelector.allEveryRound
    )
    assert(
      steps.messages.contains("alpha: 1 finding\n- I1.1 A1"),
      steps.messages.mkString("\n")
    )
    assert(
      steps.messages.contains("beta: 1 finding\n- I2.1 B1"),
      steps.messages.mkString("\n")
    )

  test("a whole-run review with no starting commit fails the flow"):
    val steps = new ReviewLoopFixture.StepCapture
    val run = ReviewLoopFixture.runWithoutStartingCommit(steps.dispatcher)
    import run.given
    val _ = intercept[OrcaFlowException]:
      reviewOnce(
        List(asReviewer(new FakeAgent("never-runs"))),
        task,
        ReviewDiff.WholeRun,
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    assertEquals(steps.messages.filter(_.startsWith("skipping")), Nil)

  test("render lists each finding with its reviewer, location and suggestion"):
    val f = finding("Null deref").copy(
      location = Some(Location("a.scala", Some(3))),
      suggestion = Some("check for null")
    )
    val text = ReviewReport(
      task,
      List("a.scala"),
      List(ReviewerFindings("alpha", List(f)))
    ).render
    assert(text.contains("## Review: PR o/r#1: add x"), text)
    assert(text.contains("1 finding(s) from 1 reviewer(s)"), text)
    assert(text.contains("- **Null deref** (alpha) — `a.scala:3`"), text)
    assert(text.contains("  - suggestion: check for null"), text)

  test("render says so when nothing was found"):
    val text = ReviewReport(
      task,
      List("a.scala"),
      List(ReviewerFindings("alpha", Nil))
    ).render
    assert(text.endsWith("No findings reported."), text)
