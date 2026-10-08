package orca.review

import orca.{FlowContext, TestFlowContext}
import orca.events.EventDispatcher

class ReviewOnceTest extends munit.FunSuite:
  private given FlowContext = new TestFlowContext(new EventDispatcher(Nil))
  private given orca.InStage = orca.InStage.unsafe

  private val target =
    ReviewTarget(
      summary = "PR o/r#1: add x",
      diffPath = ".orca/review.diff",
      changedFiles = List("a.scala")
    )

  private def picker(names: String*): ReviewerSelector =
    ReviewerSelector.agentDriven(
      new FakeAgent("picker", List(SelectedReviewers(names.toList))).agent
    )

  test("every selected reviewer's findings come back attributed to it"):
    val a = new FakeAgent("alpha", List(ReviewResult(List(finding("A1")))))
    val b = new FakeAgent("beta", List(ReviewResult(Nil)))
    val report = reviewOnce(
      List(asReviewer(a), asReviewer(b)),
      target,
      ReviewerSelector.allEveryRound
    )
    assertEquals(
      report.byReviewer.sortBy(_.reviewer),
      List(
        ReviewerFindings("alpha", List(finding("A1"))),
        ReviewerFindings("beta", Nil)
      )
    )

  test("a reviewer is told where the diff is and what is under review"):
    val a = new FakeAgent("alpha", List(ReviewResult(Nil)))
    val _ =
      reviewOnce(List(asReviewer(a)), target, ReviewerSelector.allEveryRound)
    val prompt = a.seenPrompts.head
    assert(prompt.contains(".orca/review.diff"), prompt)
    assert(prompt.contains("PR o/r#1: add x"), prompt)
    assert(prompt.contains(ReviewLoopPrompts.ReviewOnce), prompt)

  test("reviewer runs are tagged with the cost role"):
    val a = new FakeAgent("alpha", List(ReviewResult(Nil)))
    val _ =
      reviewOnce(List(asReviewer(a)), target, ReviewerSelector.allEveryRound)
    assertEquals(a.seenIdentities, List(("alpha", Some("reviewer"))))

  test("a reviewer whose files pattern matches nothing is not run"):
    val scala = new FakeAgent("scala", List(ReviewResult(Nil)))
    val docs = new FakeAgent("docs")
    val report = reviewOnce(
      List(asReviewer(scala), asReviewer(docs, filePattern = Some("\\.md$".r))),
      target,
      picker("scala", "docs")
    )
    assertEquals(report.byReviewer.map(_.reviewer), List("scala"))
    assertEquals(docs.seenPrompts, Nil)

  test("an empty pick falls back to all eligible reviewers"):
    val a = new FakeAgent("alpha", List(ReviewResult(Nil)))
    val b = new FakeAgent("beta", List(ReviewResult(Nil)))
    val report =
      reviewOnce(List(asReviewer(a), asReviewer(b)), target, picker("nobody"))
    assertEquals(
      report.byReviewer.map(_.reviewer).sorted,
      List("alpha", "beta")
    )

  test("render lists each finding with its reviewer, location and suggestion"):
    val f = finding("Null deref").copy(
      location = Some(Location("a.scala", Some(3))),
      suggestion = Some("check for null")
    )
    val text =
      ReviewReport(target, List(ReviewerFindings("alpha", List(f)))).render
    assert(text.contains("## Review: PR o/r#1: add x"), text)
    assert(text.contains("1 finding(s) from 1 reviewer(s)"), text)
    assert(text.contains("- **Null deref** (alpha) — `a.scala:3`"), text)
    assert(text.contains("  - suggestion: check for null"), text)

  test("render says so when nothing was found"):
    val text = ReviewReport(target, List(ReviewerFindings("alpha", Nil))).render
    assert(text.endsWith("No findings reported."), text)
