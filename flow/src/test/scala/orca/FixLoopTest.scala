package orca

class FixLoopTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(description: String): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = 1.0,
      description = description,
      file = None,
      line = None,
      suggestion = None
    )

  /** Evaluator that returns a scripted sequence of ReviewResults. */
  private def scripted(results: List[ReviewResult]): () => ReviewResult =
    val it = results.iterator
    () =>
      if it.hasNext then it.next()
      else throw new IllegalStateException("evaluator exhausted")

  test("returns empty IgnoredIssues when the first evaluation is clean"):
    given FlowContext = ctx
    val result = fixLoop(
      evaluate = scripted(List(ReviewResult.empty)),
      fix = _ => throw new AssertionError("fix must not be called when clean")
    )
    assertEquals(result, IgnoredIssues(Nil))

  test("terminates when fix ignores everything and re-evaluation is clean"):
    given FlowContext = ctx
    val i = issue("nit")
    val result = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(issues = List(i), summary = "one nit"),
          ReviewResult.empty
        )
      ),
      fix = found => IgnoredIssues(found.map(IgnoredIssue(_, "by design")))
    )
    assertEquals(
      result.issues.map(_.issue.description),
      List("nit")
    )
    assertEquals(result.issues.head.reason, "by design")

  test("each iteration emits its own stage and a found-N step"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val i = issue("nit")
    val _ = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(issues = List(i), summary = "one nit"),
          ReviewResult.empty
        )
      ),
      fix = found => IgnoredIssues(found.map(IgnoredIssue(_, "by design")))
    )
    val starts = seen.get().reverse.collect {
      case OrcaEvent.StageStarted(name) => name
    }
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    // Iteration 1 (issue found, fix attempted) and Iteration 2
    // (re-eval clean) each open a stage; the count + per-issue
    // surfacing happens via Steps inside the stage.
    assertEquals(starts.filter(_.startsWith("Iteration ")), List("Iteration 1", "Iteration 2"))
    assert(
      steps.exists(_ == "Found 1 review comment"),
      s"expected a 'Found 1 review comment' step; got: $steps"
    )

  test("each remaining issue surfaces as a Step in the iteration stage"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val real = ReviewIssue(
      severity = Severity.Warning,
      confidence = 0.9,
      description = "Unbounded growth in `processBatch`",
      file = Some("src/main/Foo.scala"),
      line = Some(42),
      suggestion = Some("stream batches instead of buffering")
    )
    val _ = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(issues = List(real), summary = "1 finding"),
          ReviewResult.empty
        )
      ),
      fix = found => IgnoredIssues(found.map(IgnoredIssue(_, "won't fix")))
    )
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    val issueStep = steps.find(_.contains("Unbounded growth"))
      .getOrElse(fail(s"expected an issue Step; got: $steps"))
    assert(
      issueStep.contains("[Warning]"),
      s"expected severity prefix; got: $issueStep"
    )
    assert(
      issueStep.contains("at src/main/Foo.scala:42"),
      s"expected location line; got: $issueStep"
    )
    assert(
      issueStep.contains("suggestion: stream batches"),
      s"expected suggestion line; got: $issueStep"
    )

  test("loop emits a 'Discarded' Step when fix only ignores issues"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val i = issue("nit")
    val _ = fixLoop(
      evaluate = scripted(
        List(
          ReviewResult(issues = List(i), summary = "one nit"),
          ReviewResult.empty
        )
      ),
      fix = found => IgnoredIssues(found.map(IgnoredIssue(_, "by design")))
    )
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    assert(
      steps.exists(_.startsWith("Discarded 1 review comment")),
      s"expected a 'Discarded' Step; got: $steps"
    )

  test("loop emits a 'Bailed out' Step when fix makes no progress"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val i = issue("stuck")
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult(issues = List(i), summary = "stuck"))),
      // Returning empty IgnoredIssues triggers the "fix made no progress" bail.
      fix = _ => IgnoredIssues(Nil)
    )
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    assert(
      steps.exists(_.startsWith("Bailed out")),
      s"expected a 'Bailed out' Step; got: $steps"
    )

  test("loop emits 'No review comments' when first eval is already clean"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val _ = fixLoop(
      evaluate = scripted(List(ReviewResult.empty)),
      fix = _ => throw new AssertionError("fix must not be called")
    )
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    assert(
      steps.contains("No review comments"),
      s"expected the 'no comments' Step; got: $steps"
    )

  test(
    "stops at maxIterations and records remaining issues as 'max iterations reached'"
  ):
    given FlowContext = ctx
    // Each iteration ignores one new trivial issue so the loop actually
    // progresses past the no-progress guard, up until maxIterations is hit
    // with a fresh issue still pending.
    val freshIssues = (1 to 100).map(n => issue(s"#$n")).toList
    val evalIt = freshIssues.iterator
    val result = fixLoop(
      evaluate =
        () => ReviewResult(issues = List(evalIt.next()), summary = "issue"),
      fix = found =>
        IgnoredIssues(
          found.map(i => IgnoredIssue(i, s"accepted ${i.description}"))
        ),
      maxIterations = 3
    )
    // 3 fix attempts accept issues 1..3; the 4th evaluate returns issue #4,
    // which hits the max-iterations cap and is folded in with that reason.
    assertEquals(result.issues.size, 4)
    assertEquals(
      result.issues.last.issue.description,
      "#4"
    )
    assert(result.issues.last.reason.contains("max iterations"))

  test("filters out previously-ignored issues from subsequent evaluations"):
    given FlowContext = ctx
    val repeated = issue("known nit")
    // evaluate always reports the same issue; fix ignores once; loop should
    // terminate after the second evaluate since the issue is already ignored.
    var attempts = 0
    val result = fixLoop(
      evaluate = () =>
        attempts += 1
        ReviewResult(issues = List(repeated), summary = "still there")
      ,
      fix = _ => IgnoredIssues(List(IgnoredIssue(repeated, "ok"))),
      maxIterations = 10
    )
    assertEquals(result.issues.map(_.reason), List("ok"))
    assertEquals(attempts, 2)

  test("bails out early when fix makes no progress"):
    given FlowContext = ctx
    val stubborn = issue("unfixable")
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(issues = List(stubborn), summary = "persists")
      ,
      fix = _ => IgnoredIssues(Nil),
      maxIterations = 10
    )
    assertEquals(evaluates, 1, "should bail after first fix returns no ignores")
    assertEquals(result.issues.size, 1)
    assert(result.issues.head.reason.contains("no progress"))
