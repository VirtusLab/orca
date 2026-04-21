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
