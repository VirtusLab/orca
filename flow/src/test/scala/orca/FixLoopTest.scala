package orca

class FixLoopTest extends munit.FunSuite:

  private def ctx: FlowContext =
    new TestFlowContext(new EventDispatcher(Nil))

  private def issue(description: String): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = 1.0,
      shortSummary = description,
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
    val i1 = issue("nit-1")
    val i2 = issue("nit-2")
    val _ = fixLoop(
      // Two issues per round so the fix can ignore one (Progressed)
      // without tripping the "all ignored" halt — that lets the loop
      // run a second iteration we want to assert on.
      evaluate = scripted(
        List(
          ReviewResult(issues = List(i1, i2), summary = "two nits"),
          ReviewResult.empty
        )
      ),
      fix = found => IgnoredIssues(List(IgnoredIssue(found.head, "by design")))
    )
    val starts = seen.get().reverse.collect {
      case OrcaEvent.StageStarted(name) => name
    }
    val steps = seen.get().reverse.collect {
      case OrcaEvent.Step(msg) => msg
    }
    assertEquals(starts.filter(_.startsWith("Iteration ")), List("Iteration 1", "Iteration 2"))
    // Per-iteration outcome lines. Iteration 1 had 2 issues, fix
    // ignored 1 (Progressed); iteration 2's eval was empty (Clean).
    assert(steps.contains("Fixed review comments"), s"got: $steps")
    assert(steps.contains("No review comments"), s"got: $steps")

  test("formatIssue includes severity, summary, location, and suggestion"):
    // `fixLoop` no longer emits per-issue Steps — that's `evaluate`'s
    // job, via `formatReviewerOutcome` which delegates to
    // `formatIssue`. Pin the rendering directly.
    val real = ReviewIssue(
      severity = Severity.Warning,
      confidence = 0.9,
      shortSummary = "Unbounded growth in `processBatch`",
      description = "Unbounded growth in `processBatch`",
      file = Some("src/main/Foo.scala"),
      line = Some(42),
      suggestion = Some("stream batches instead of buffering")
    )
    val rendered = orca.formatIssue(real)
    assert(rendered.contains("[Warning]"), s"missing severity; got: $rendered")
    assert(rendered.contains("Unbounded growth"), s"missing summary; got: $rendered")
    assert(rendered.contains("at src/main/Foo.scala:42"), s"missing location; got: $rendered")
    assert(rendered.contains("suggestion: stream batches"), s"missing suggestion; got: $rendered")

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
    // Each round produces 2 fresh issues; fix ignores one (Progressed
    // because newlyIgnored < remaining). The other passes through into
    // the next round's eval-fresh-pair and is dropped by the script's
    // refresh, so the loop keeps making partial progress until the cap.
    val freshIssues = (1 to 100).grouped(2).map(g => g.map(n => issue(s"#$n")).toList)
    val result = fixLoop(
      evaluate =
        () => ReviewResult(issues = freshIssues.next(), summary = "two"),
      // Ignore only the first issue → newlyIgnored=1 < remaining=2 →
      // Progressed, not AllIgnored.
      fix = found =>
        IgnoredIssues(List(IgnoredIssue(found.head, s"accepted ${found.head.description}"))),
      maxIterations = 3
    )
    // 3 fix attempts accept one issue each (3 entries with "accepted" reason);
    // the 4th evaluate returns 2 fresh issues, hitting the cap → both folded
    // in with "max iterations" reason. 5 entries total.
    assertEquals(result.issues.size, 5)
    val byReason = result.issues.groupMap(_.reason)(_.issue.description)
    assertEquals(
      byReason.keys.toSet.count(_.contains("max iterations")),
      1,
      s"expected one max-iterations reason group; got reasons: ${byReason.keys}"
    )

  test("filters out previously-ignored issues from subsequent evaluations"):
    given FlowContext = ctx
    val repeated = issue("known nit")
    val fresh = issue("new finding")
    // First eval has both issues; fix ignores `repeated`. Second eval returns
    // [repeated, fresh] again; the filter removes `repeated` so the iteration
    // sees only `fresh`. Without the ignoredSet filter the loop would
    // re-attempt `repeated` indefinitely.
    var attempts = 0
    val _ = fixLoop(
      evaluate = () =>
        attempts += 1
        ReviewResult(issues = List(repeated, fresh), summary = "two")
      ,
      // Always ignore `repeated` only — leaves `fresh` unfixed so the
      // loop's NoProgress guard fires after we've exercised the filter.
      fix = _ => IgnoredIssues(List(IgnoredIssue(repeated, "ok"))),
      maxIterations = 10
    )
    // Iter 0: sees both, ignores `repeated`, Progressed.
    // Iter 1: sees [repeated, fresh] from eval but filter drops `repeated`;
    //   fix ignores `repeated` again (fix is constant) but newlyIgnored=1 ==
    //   remaining=1 (just `fresh`), so AllIgnored halts.
    assertEquals(attempts, 2, "filter should expose only `fresh` to round 2")

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

  test("halts cleanly when fix marks every remaining issue as ignored"):
    // The fix-as-environmental-disclaimer case: agent says it can't
    // address anything in code, returns the input as IgnoredIssues
    // wholesale. Re-evaluating would just re-find the same things
    // (possibly worded differently), so the loop halts with the
    // ignored entries folded into the discarded summary.
    val seen = new java.util.concurrent.atomic.AtomicReference[List[OrcaEvent]](Nil)
    val listener = new OrcaListener:
      def onEvent(event: OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given FlowContext = new TestFlowContext(new EventDispatcher(List(listener)))
    val unfixable = issue("cargo: command not found")
    var evaluates = 0
    val result = fixLoop(
      evaluate = () =>
        evaluates += 1
        ReviewResult(issues = List(unfixable), summary = "env"),
      fix = found =>
        IgnoredIssues(found.map(IgnoredIssue(_, "missing toolchain"))),
      maxIterations = 10
    )
    assertEquals(evaluates, 1, "should not re-evaluate after AllIgnored halt")
    assertEquals(result.issues.map(_.reason), List("missing toolchain"))
    val steps = seen.get().reverse.collect { case OrcaEvent.Step(m) => m }
    assert(
      steps.exists(_.startsWith("All ")),
      s"expected an 'All N marked as won't-fix' step; got: $steps"
    )
    assert(
      steps.exists(_.startsWith("Discarded ")),
      s"AllIgnored exits cleanly so the closing message should be Discarded; got: $steps"
    )
