package orca.review

import orca.{
  EventDispatcher,
  FlowContext,
  OrcaEvent,
  OrcaListener,
  TestFlowContext,
  Title
}

import java.util.concurrent.atomic.AtomicReference

class FixLoopTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(title: String): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = 1.0,
      title = Title(title),
      description = title,
      file = None,
      line = None,
      suggestion = None
    )

  /** Recording listener; reads back collected events in arrival order. */
  private class Recorder extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def steps: List[String] = seen
      .get()
      .reverse
      .collect:
        case OrcaEvent.Step(msg) => msg
    def stageNames: List[String] = seen
      .get()
      .reverse
      .collect:
        case OrcaEvent.StageStarted(name) => name

  /** Evaluator that returns a scripted sequence; throws when exhausted. */
  private def scripted(results: List[ReviewResult]): () => ReviewResult =
    val it = results.iterator
    () =>
      if it.hasNext then it.next()
      else throw new IllegalStateException("evaluator exhausted")

  test("clean first evaluation returns no ignored and never calls fix"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val result = fixLoop(
      evaluate = scripted(List(ReviewResult.empty)),
      fix = _ => throw new AssertionError("fix must not be called when clean")
    )
    assertEquals(result, IgnoredIssues(Nil))
    assert(rec.steps.contains("No review comments"))

  test(
    "re-evaluates after a non-empty `fixed`, accumulates ignored across rounds"
  ):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val a = issue("a")
    val b = issue("b")
    val c = issue("c")
    val result = fixLoop(
      // Round 1: two issues. Round 2: one fresh issue. Round 3: clean.
      evaluate = scripted(
        List(
          ReviewResult(List(a, b)),
          ReviewResult(List(c)),
          ReviewResult.empty
        )
      ),
      // Round 1: fix `a`, ignore `b`. Round 2: fix `c`. Round 3 isn't reached
      // because evaluator returns clean before fix is called.
      fix = found =>
        if found.map(_.title.value).toSet == Set("a", "b") then
          FixOutcome(
            fixed = List(Title("a")),
            ignored = List(IgnoredIssue(Title("b"), "out of scope"))
          )
        else FixOutcome(fixed = List(Title("c")), ignored = Nil)
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("b"), "out of scope"))
    )
    assertEquals(
      rec.stageNames.filter(_.startsWith("Iteration ")),
      List("Iteration 1", "Iteration 2", "Iteration 3")
    )

  test("halts when `fixed` is empty, regardless of `ignored` size"):
    given FlowContext = ctx
    val i = issue("x")
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(List(i))
      ,
      fix = _ => FixOutcome(Nil, List(IgnoredIssue(Title("x"), "won't fix")))
    )
    assertEquals(evaluates, 1, "must not re-evaluate when nothing was fixed")
    assertEquals(result.issues, List(IgnoredIssue(Title("x"), "won't fix")))

  test("caps at maxIterations and marks remaining issues with that reason"):
    given FlowContext = ctx
    // The fixer always claims one fix, so progress is reported every round
    // and only the maxIterations cap can stop the loop.
    val stubborn = issue("infinite")
    val result = fixLoop(
      evaluate = () => ReviewResult(List(stubborn)),
      fix = _ => FixOutcome(List(Title("infinite")), Nil),
      maxIterations = 2
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("infinite"), "max iterations (2) reached"))
    )

  test("formatIssue renders severity, title, location, and suggestion"):
    val real = ReviewIssue(
      severity = Severity.Warning,
      confidence = 0.9,
      title = Title("Unbounded growth in `processBatch`"),
      description = "Unbounded growth in `processBatch`",
      file = Some("src/main/Foo.scala"),
      line = Some(42),
      suggestion = Some("stream batches instead of buffering")
    )
    val rendered = formatIssue(real)
    assert(rendered.contains("[Warning]"), s"missing severity: $rendered")
    assert(rendered.contains("Unbounded growth"), s"missing title: $rendered")
    assert(
      rendered.contains("at src/main/Foo.scala:42"),
      s"missing location: $rendered"
    )
    assert(
      rendered.contains("suggestion: stream batches"),
      s"missing suggestion: $rendered"
    )
