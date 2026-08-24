package orca.review

import orca.{FlowContext}
import orca.plan.Title
import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.{TestFlowContext}

import java.util.concurrent.atomic.AtomicReference

class FixLoopTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

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
    // Iterations run under the caller's task stage (ADR 0018 §2.2), so they
    // surface as Step events rather than StageStarted.
    assertEquals(
      rec.steps.filter(_.startsWith("Iteration ")),
      List("Iteration 1", "Iteration 2", "Iteration 3")
    )

  test("a finding declined in one round and fixed in the next is not ignored"):
    given FlowContext = ctx
    // The decline is only the fixer's position at the time; once it fixes the
    // same finding, carrying the old entry would report fixed work as ignored.
    val result = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(List(issue("nit"), issue("driver"))),
          ReviewResult(List(issue("nit"))),
          ReviewResult.empty
        )
      ),
      fix = found =>
        if found.size == 2 then
          FixOutcome(
            fixed = List(Title("driver")),
            ignored = List(IgnoredIssue(Title("nit"), "deliberate"))
          )
        else FixOutcome(fixed = List(Title("nit")), ignored = Nil)
    )
    assertEquals(result, IgnoredIssues(Nil))

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

  test("records what the fixer left unaccounted when it reports no fixes"):
    given FlowContext = ctx
    // A malformed or incomplete reply accounts for nothing. The issue is still
    // open, so it must come back rather than vanish from the result.
    val result = fixLoop(
      evaluate = scripted(List(ReviewResult(List(issue("x"))))),
      fix = _ => FixOutcome(Nil, Nil)
    )
    assertEquals(
      result.issues,
      List(IgnoredIssue(Title("x"), "fixer reported no fixes"))
    )

  test("the fix line says another review round follows the fixes"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate =
        scripted(List(ReviewResult(List(issue("a"))), ReviewResult.empty)),
      fix = _ => FixOutcome(List(Title("a")), Nil)
    )
    assert(
      rec.steps.contains(
        "Fixed 1, ignored 0; reviewing again after the fixes"
      ),
      rec.steps.mkString("\n")
    )

  test("the fix line promises no further review when nothing was fixed"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult(List(issue("a"))))),
      fix = _ => FixOutcome(Nil, List(IgnoredIssue(Title("a"), "won't fix")))
    )
    assert(rec.steps.contains("Fixed 0, ignored 1"), rec.steps.mkString("\n"))

  test("the halt exit says why the loop stopped"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult(List(issue("x"))))),
      fix = _ => FixOutcome(Nil, Nil)
    )
    assert(
      rec.steps.contains("Fixer reported no fixes; ending review"),
      s"halt must be announced: ${rec.steps}"
    )

  test("an echoed issue key resolves even when the fixer rewrote the title"):
    given FlowContext = ctx
    // Keys are positional and copy exactly, which is what makes the match hold
    // where a paraphrased title doesn't.
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        if evaluates == 1 then ReviewResult(List(issue("x")))
        else ReviewResult.empty
      ,
      fix = _ => FixOutcome(List(Title("I1.1 sorted out the x problem")), Nil)
    )
    assertEquals(evaluates, 2, "a resolved fix must let the loop re-evaluate")
    assertEquals(result, IgnoredIssues(Nil))

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

  test("the library default cap is 3 fix attempts, so 4 evaluations"):
    given FlowContext = ctx
    // The loop never converges and `maxIterations` is omitted, so the
    // evaluation count reads the shared default back.
    var evaluates = 0
    val _ = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(List(issue("infinite")))
      ,
      fix = _ => FixOutcome(List(Title("infinite")), Nil)
    )
    assertEquals(evaluates, 4)

  test("the cap exit names the issues it leaves open"):
    // Callers discard the returned IgnoredIssues, so this message is the only
    // place a too-low cap becomes visible in a run.
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = () => ReviewResult(List(issue("still broken"))),
      fix = _ => FixOutcome(List(Title("still broken")), Nil),
      maxIterations = 1
    )
    assert(
      rec.steps.contains(
        """Unresolved findings (1):
          |  - [Warning] still broken
          |    max iterations (1) reached""".stripMargin
      ),
      s"cap exit must name what it left open: ${rec.steps}"
    )

  test("formatIssue renders severity, title, location, and suggestion"):
    val real = ReviewIssue(
      severity = Severity.Warning,
      confidence = Confidence.orThrow(0.9),
      title = Title("Unbounded growth in `processBatch`"),
      description = "Unbounded growth in `processBatch`",
      location = Some(Location("src/main/Foo.scala", Some(42))),
      suggestion = Some("stream batches instead of buffering")
    )
    val rendered = formatIssue("I1.1", real)
    assert(rendered.startsWith("- I1.1 [Warning]"), s"missing key: $rendered")
    assert(rendered.contains("Unbounded growth"), s"missing title: $rendered")
    assert(
      rendered.contains("at src/main/Foo.scala:42"),
      s"missing location: $rendered"
    )
    assert(
      rendered.contains("suggestion: stream batches"),
      s"missing suggestion: $rendered"
    )

  test("formatReviewerOutcome notes gate rejects on a clean review"):
    // A reviewer whose findings were all gated out must not read as quiet.
    assertEquals(
      formatReviewerOutcome("loud", Nil, 3),
      "loud: 0 issues (3 below the confidence gate)"
    )

  test("formatReviewerOutcome notes gate rejects in the heading above bullets"):
    val rendered =
      formatReviewerOutcome("loud", KeyedIssue.forAgent(0, List(issue("x"))), 1)
    assertEquals(
      rendered.linesIterator.next(),
      "loud: 1 issue (1 below the confidence gate)"
    )
    assert(rendered.contains("- I1.1 [Warning] x"), rendered)

  test("formatReviewerOutcome bullets carry the agent's own key index"):
    // Keys name the agent that reported the finding, so the second agent's
    // findings are I2.n — that is what the fixer echoes back.
    val rendered = formatReviewerOutcome(
      "second",
      KeyedIssue.forAgent(1, List(issue("x"), issue("y"))),
      0
    )
    assert(rendered.contains("- I2.1 [Warning] x"), rendered)
    assert(rendered.contains("- I2.2 [Warning] y"), rendered)

  test("formatIssue renders a file-only location with no trailing line"):
    // A line without a file is unrepresentable (Location pairs them); this
    // pins the still-valid file-without-line case.
    val fileOnly = ReviewIssue(
      severity = Severity.Info,
      confidence = Confidence.orThrow(0.5),
      title = Title("Nit"),
      description = "Nit",
      location = Some(Location("src/main/Foo.scala", None)),
      suggestion = None
    )
    val rendered = formatIssue("I1.1", fileOnly)
    assert(
      rendered.contains("at src/main/Foo.scala") &&
        !rendered.contains("src/main/Foo.scala:"),
      s"expected a file-only location with no line; got: $rendered"
    )
