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

  test("clean first evaluation returns no declined and never calls fix"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val result = fixLoop(
      evaluate = scripted(List(ReviewResult.empty)),
      fix = _ => throw new AssertionError("fix must not be called when clean")
    )
    assertEquals(result, OpenFindings(Nil))
    assert(rec.steps.contains("No findings"))

  test(
    "re-evaluates after a non-empty `fixed`, accumulates declined across rounds"
  ):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val a = finding("a")
    val b = finding("b")
    val c = finding("c")
    val result = fixLoop(
      // Round 1: two findings. Round 2: one fresh finding. Round 3: clean.
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
            declined = List(DeclinedFinding(Title("b"), "out of scope"))
          )
        else FixOutcome(fixed = List(Title("c")), declined = Nil)
    )
    assertEquals(
      result.findings,
      List(OpenFinding(Title("b"), OpenReason.Declined("out of scope"), None))
    )
    // Iterations run under the caller's task stage (ADR 0018 §2.2), so they
    // surface as Step events rather than StageStarted.
    assertEquals(
      rec.steps.filter(_.startsWith("Iteration ")),
      List("Iteration 1", "Iteration 2", "Iteration 3")
    )

  test("a finding declined in one round and fixed in the next is not declined"):
    given FlowContext = ctx
    // The decline is only the fixer's position at the time; once it fixes the
    // same finding, carrying the old entry would report fixed work as declined.
    val result = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(List(finding("nit"), finding("driver"))),
          ReviewResult(List(finding("nit"))),
          ReviewResult.empty
        )
      ),
      fix = found =>
        if found.size == 2 then
          FixOutcome(
            fixed = List(Title("driver")),
            declined = List(DeclinedFinding(Title("nit"), "deliberate"))
          )
        else FixOutcome(fixed = List(Title("nit")), declined = Nil)
    )
    assertEquals(result, OpenFindings(Nil))

  test("halts when `fixed` is empty, regardless of `declined` size"):
    given FlowContext = ctx
    val i = finding("x")
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(List(i))
      ,
      fix = _ => FixOutcome(Nil, List(DeclinedFinding(Title("x"), "won't fix")))
    )
    assertEquals(evaluates, 1, "must not re-evaluate when nothing was fixed")
    assertEquals(
      result.findings,
      List(OpenFinding(Title("x"), OpenReason.Declined("won't fix"), None))
    )

  test("records what the fixer left unaccounted when it reports no fixes"):
    given FlowContext = ctx
    // A malformed or incomplete reply accounts for nothing. The finding is
    // still open, so it must come back rather than vanish from the result.
    val result = fixLoop(
      evaluate = scripted(List(ReviewResult(List(finding("x"))))),
      fix = _ => FixOutcome(Nil, Nil)
    )
    assertEquals(
      result.findings,
      List(OpenFinding(Title("x"), OpenReason.NoFixes, None))
    )

  test("the fix line says another review round follows the fixes"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate =
        scripted(List(ReviewResult(List(finding("a"))), ReviewResult.empty)),
      fix = _ => FixOutcome(List(Title("a")), Nil)
    )
    assert(
      rec.steps.contains(
        "Fixed 1, declined 0; reviewing again after the fixes"
      ),
      rec.steps.mkString("\n")
    )

  test("the fix line promises no further review when nothing was fixed"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult(List(finding("a"))))),
      fix = _ => FixOutcome(Nil, List(DeclinedFinding(Title("a"), "won't fix")))
    )
    assert(rec.steps.contains("Fixed 0, declined 1"), rec.steps.mkString("\n"))

  test("the halt exit says why the loop stopped"):
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult(List(finding("x"))))),
      fix = _ => FixOutcome(Nil, Nil)
    )
    assert(
      rec.steps.contains("Fixer reported no fixes; ending review"),
      s"halt must be announced: ${rec.steps}"
    )

  test("an echoed finding key resolves even when the fixer rewrote the title"):
    given FlowContext = ctx
    // Keys are positional and copy exactly, which is what makes the match hold
    // where a paraphrased title doesn't.
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        if evaluates == 1 then ReviewResult(List(finding("x")))
        else ReviewResult.empty
      ,
      fix = _ => FixOutcome(List(Title("I1.1 sorted out the x problem")), Nil)
    )
    assertEquals(evaluates, 2, "a resolved fix must let the loop re-evaluate")
    assertEquals(result, OpenFindings(Nil))

  test("caps at maxIterations and marks remaining findings with that reason"):
    given FlowContext = ctx
    // The fixer always claims one fix, so progress is reported every round
    // and only the maxIterations cap can stop the loop.
    val stubborn = finding("infinite")
    val result = fixLoop(
      evaluate = () => ReviewResult(List(stubborn)),
      fix = _ => FixOutcome(List(Title("infinite")), Nil),
      maxIterations = 2
    )
    assertEquals(
      result.findings,
      List(OpenFinding(Title("infinite"), OpenReason.CapReached(2), None))
    )

  test("the library default cap is 3 fix attempts, so 4 evaluations"):
    given FlowContext = ctx
    // The loop never converges and `maxIterations` is omitted, so the
    // evaluation count reads the shared default back.
    var evaluates = 0
    val _ = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(List(finding("infinite")))
      ,
      fix = _ => FixOutcome(List(Title("infinite")), Nil)
    )
    assertEquals(evaluates, 4)

  test("the cap exit names the findings it leaves open"):
    // Callers discard the returned OpenFindings, so this message is the only
    // place a too-low cap becomes visible in a run.
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val _ = fixLoop(
      evaluate = () => ReviewResult(List(finding("still broken"))),
      fix = _ => FixOutcome(List(Title("still broken")), Nil),
      maxIterations = 1
    )
    assert(
      rec.steps.contains(
        s"""Findings still open (1):
           |  - still broken
           |    ${OpenReason.CapReached(1).describe}""".stripMargin
      ),
      s"cap exit must name what it left open: ${rec.steps}"
    )

  test("an exit points at the location a round before it gave"):
    // `OpenFinding` carries only a title, so the location has to be carried
    // across rounds: only round one reports the located finding, and the exit
    // round is clean.
    val rec = new Recorder
    given FlowContext = new TestFlowContext(new EventDispatcher(List(rec)))
    val located = ReviewFinding(
      title = Title("still broken"),
      description = "still broken",
      location = Some(Location("src/main/Foo.scala", Some(42))),
      suggestion = None
    )
    val _ = fixLoop(
      evaluate = scripted(
        List(ReviewResult(List(located, finding("driver"))), ReviewResult.empty)
      ),
      fix = _ =>
        FixOutcome(
          fixed = List(Title("driver")),
          declined = List(DeclinedFinding(Title("still broken"), "won't fix"))
        )
    )
    assert(
      rec.steps.contains(
        """Findings still open (1):
          |  - still broken
          |    at src/main/Foo.scala:42
          |    won't fix""".stripMargin
      ),
      s"exit must point at the finding: ${rec.steps}"
    )

  test("formatFinding renders the key, title, location, and suggestion"):
    val real = ReviewFinding(
      title = Title("Unbounded growth in `processBatch`"),
      description = "Unbounded growth in `processBatch`",
      location = Some(Location("src/main/Foo.scala", Some(42))),
      suggestion = Some("stream batches instead of buffering")
    )
    val rendered = formatFinding("I1.1", real)
    assert(
      rendered.startsWith("- I1.1 Unbounded growth in `processBatch`"),
      rendered
    )
    assert(
      rendered.contains("at src/main/Foo.scala:42"),
      s"missing location: $rendered"
    )
    assert(
      rendered.contains("suggestion: stream batches"),
      s"missing suggestion: $rendered"
    )

  test("formatReviewerOutcome bullets carry the agent's own key index"):
    // Keys name the agent that reported the finding, so the second agent's
    // findings are I2.n — that is what the fixer echoes back.
    val rendered = formatReviewerOutcome(
      "second",
      KeyedFinding.forAgent(1, List(finding("x"), finding("y")))
    )
    assert(rendered.contains("- I2.1 x"), rendered)
    assert(rendered.contains("- I2.2 y"), rendered)

  test("formatFinding renders a file-only location with no trailing line"):
    // A line without a file is unrepresentable (Location pairs them); this
    // pins the still-valid file-without-line case.
    val fileOnly = ReviewFinding(
      title = Title("Nit"),
      description = "Nit",
      location = Some(Location("src/main/Foo.scala", None)),
      suggestion = None
    )
    val rendered = formatFinding("I1.1", fileOnly)
    assert(
      rendered.contains("at src/main/Foo.scala") &&
        !rendered.contains("src/main/Foo.scala:"),
      s"expected a file-only location with no line; got: $rendered"
    )
