package orca.shell.flows

import orca.review.DefaultMaxIterations

class BuiltInFlowsTest extends munit.FunSuite:

  private val resourcePrefix = "/orca/shell/flows/"

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    assert(stream != null, s"missing resource $name")
    try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  private def indexNames: List[String] =
    resourceText("index").linesIterator.filter(_.nonEmpty).toList

  test("the generated index lists the built-in flows"):
    assertEquals(
      indexNames.sorted,
      List(
        "implement-enhanced.sc",
        "implement-interactive.sc",
        "implement.sc",
        "issue-pr-bugfix.sc",
        "issue-pr.sc",
        "review.sc",
        "simple.sc"
      )
    )

  test("every indexed flow resource is readable and non-empty"):
    indexNames.foreach(name => assert(resourceText(name).trim.nonEmpty, name))

  test("every fix-loop call in a flow states the cap it runs under"):
    // Each call states the cap instead of inheriting it, so a reader of the flow
    // knows which cap the run used. `reviewThenFix` is one pass and takes no
    // cap, so it is not counted — a cap written into one of those calls shows up
    // here as a cap too many. `flows/` is outside scalafmt's scope, hence the
    // loose spacing allowed in the regex. Counting per file is a heuristic, not
    // a per-call proof: it catches a call added without a cap, but not two caps
    // on one of two calls.
    val calls = "\\b(?:reviewAndFixLoop|fixLoop)\\(".r
    val caps = "maxIterations\\s*=\\s*\\d+".r
    val counted = indexNames.map: name =>
      val text = resourceText(name)
      (name, calls.findAllIn(text).size, caps.findAllIn(text).size)
    assert(counted.exists(_._2 > 0), "no flow calls the fix loop any more")
    assertEquals(counted.filter((_, calls, caps) => calls != caps), Nil)

  /** The flows that plan a prompt into tasks. Each reviews a task in one pass
    * and then reviews the whole run in a loop — the two halves are one shape,
    * pinned as an exact set below.
    */
  private val taskBasedFlows: List[String] = List(
    "implement-enhanced.sc",
    "implement-interactive.sc",
    "implement.sc",
    "issue-pr-bugfix.sc",
    "issue-pr.sc"
  )

  test("a single-pass task review always comes with a whole-run final review"):
    // Coupled, not optional: a flow that reviews each task in one pass takes
    // the fixer's word for those fixes, and the final review is what checks
    // them. Either half alone — a single pass with nothing verifying it, or a
    // task loop where the shape says one pass — is the regression to catch, so
    // both sets are pinned exactly rather than existentially.
    assertEquals(
      indexNames.filter(resourceText(_).contains("reviewThenFix(")).sorted,
      taskBasedFlows
    )
    assertEquals(
      indexNames.filter(resourceText(_).contains("\"Final review\"")).sorted,
      taskBasedFlows
    )

  /** The text of the `reviewAndFixLoop(...)` call following a flow's `"Final
    * review"` stage, cut at the call's own closing paren so the pins below
    * can't be satisfied by some other call in the file.
    */
  private def finalReviewCall(name: String): String =
    val text = resourceText(name)
    val stage = text.indexOf("\"Final review\"")
    assert(stage >= 0, s"$name has no final review stage")
    val open = text.indexOf("reviewAndFixLoop(", stage)
    assert(open >= 0, s"$name's final review does not call reviewAndFixLoop")
    val from = open + "reviewAndFixLoop".length
    var depth = 0
    var i = from
    while i == from || depth > 0 do
      text(i) match
        case '(' => depth += 1
        case ')' => depth -= 1
        case _   => ()
      i += 1
    text.substring(open, i)

  test("a flow's final review reviews the whole run"):
    // `diff = ReviewDiff.WholeRun` is what makes the stage a whole-run review
    // rather than an empty stage-scoped one — a "Final review" stage without
    // it reviews nothing. Pinned inside the final-review call itself, so it
    // can't be satisfied by another call in the file.
    taskBasedFlows.foreach: name =>
      val call = finalReviewCall(name)
      assert(call.contains("diff = ReviewDiff.WholeRun"), s"$name: $call")

  test("every flow's cap is the library default, spelled out"):
    // Whole-run and per-task loops run under the same number, so there is one
    // pin rather than two. Every cap in the file is checked, not just the first
    // — the count test above compares counts, so a flow whose task loop and
    // final review state different numbers passes it. Each flow still writes
    // the number instead of inheriting it, so raising `DefaultMaxIterations`
    // fails here until the flows follow.
    val cap = "maxIterations\\s*=\\s*(\\d+)".r
    val capped = indexNames.filter(resourceText(_).contains("maxIterations"))
    assert(capped.nonEmpty, "no flow states a cap any more")
    capped.foreach: name =>
      val text = resourceText(name)
      val stated = cap.findAllMatchIn(text).map(_.group(1)).toList
      assertEquals(stated.distinct, List(DefaultMaxIterations.toString), name)

  /** A flow's last statement, as its last non-blank line. */
  private def lastStatement(name: String): String =
    resourceText(name).linesIterator.filter(_.trim.nonEmpty).toList.last.trim

  test("every code-producing flow ends with a best-effort PR step"):
    // Two exact sets that partition the flows by which PR helper they use, so a
    // code-producing flow that drops the step — or reaches for the
    // GitHub-requiring one — shows up here, and so does a `review.sc` that
    // grows a PR step it should not have.
    val bestEffort = List(
      "implement-enhanced.sc",
      "implement-interactive.sc",
      "implement.sc",
      "simple.sc"
    )
    assertEquals(
      indexNames.filter(resourceText(_).contains("openPrIfGitHub(")).sorted,
      bestEffort
    )
    assertEquals(
      indexNames.filter(resourceText(_).contains("openPrFromBranch(")).sorted,
      List("issue-pr.sc")
    )
    // "Ends with", not merely "calls": the step pushes the branch and describes
    // it from the whole branch diff, so a call placed above the final review
    // would summarise work the review then keeps changing.
    bestEffort.foreach: name =>
      assert(lastStatement(name).startsWith("openPrIfGitHub("), name)

  test("a flow that opens its own PR records it for the lifecycle"):
    // `issue-pr-bugfix.sc` opens its PR with a bare `gh.createPr`, so the
    // handle only reaches the lifecycle — and the run only ends on the start
    // branch — if the flow records it itself.
    assert(
      resourceText("issue-pr-bugfix.sc").contains("recordOpenedPr("),
      "issue-pr-bugfix.sc must record the PR it opens"
    )

  test("no flow's header claims the removed branch-handoff flag"):
    // The argument itself no longer compiles; this catches the prose left
    // behind in a header, which no compile does.
    assertEquals(
      indexNames.filter(resourceText(_).contains("returnToStartBranch")),
      Nil
    )

  private def withTempHome(body: os.Path => Unit): Unit =
    val home = os.temp.dir(prefix = "orca-built-in-flows-test")
    try body(home)
    finally os.remove.all(home)

  test("extracted falls back to home/.cache when XDG_CACHE_HOME is relative"):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(
        Map("XDG_CACHE_HOME" -> "rel/path").get,
        home,
        "0.0.18"
      )
      assertEquals(dir, home / ".cache" / "orca" / "shell" / "0.0.18" / "flows")

  test(
    "extracted (release version) creates the flows once, unchanged on a second call"
  ):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")
      assert(os.isDir(dir))
      val expectedNames = indexNames.sorted
      assertEquals(os.list(dir).map(_.last).toList.sorted, expectedNames)
      val mtimesBefore = expectedNames.map(n => n -> os.mtime(dir / n)).toMap

      val _ = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")

      val mtimesAfter = expectedNames.map(n => n -> os.mtime(dir / n)).toMap
      assertEquals(mtimesAfter, mtimesBefore)

  test("extracted (dev version) rewrites the dep pin and injects ivy2Local"):
    withTempHome: home =>
      val runningVersion = "0.0.18+5-abc123"
      val dir = BuiltInFlows.extracted(Map.empty.get, home, runningVersion)
      val content = os.read(dir / "issue-pr.sc")
      val lines = content.linesIterator.toList
      val depLineIdx = lines.indexWhere(_.startsWith("//> using dep "))
      assert(depLineIdx >= 0, "expected a using-dep line")
      assertEquals(
        lines(depLineIdx),
        s"""//> using dep "org.virtuslab::orca:$runningVersion""""
      )
      assertEquals(lines(depLineIdx + 1), "//> using repository ivy2Local")

  test(
    "extracted (dev version) only re-materializes once per process (P1): unchanged on a second call"
  ):
    withTempHome: home =>
      val runningVersion = "0.0.18+9-def456"
      val dir = BuiltInFlows.extracted(Map.empty.get, home, runningVersion)
      val expectedNames = indexNames.sorted
      val mtimesBefore = expectedNames.map(n => n -> os.mtime(dir / n)).toMap

      val _ = BuiltInFlows.extracted(Map.empty.get, home, runningVersion)

      val mtimesAfter = expectedNames.map(n => n -> os.mtime(dir / n)).toMap
      assertEquals(mtimesAfter, mtimesBefore)

  test("extracted (release version) self-heals a half-populated leftover dir"):
    withTempHome: home =>
      // Simulates a process killed mid-extraction under the old
      // existence-keyed logic: the dir exists but only has 2 of the files.
      val dir = home / ".cache" / "orca" / "shell" / "0.0.18" / "flows"
      os.makeDir.all(dir)
      val expectedNames = indexNames.sorted
      expectedNames
        .take(2)
        .foreach(name => os.write(dir / name, "stale-partial-content"))
      assertEquals(
        os.list(dir).map(_.last).toList.sorted,
        expectedNames.take(2)
      )

      val result = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")

      assertEquals(result, dir)
      assertEquals(os.list(dir).map(_.last).toList.sorted, expectedNames)
      expectedNames.foreach(name =>
        assert(os.read(dir / name).trim.nonEmpty, name)
      )

  test("extracted (\"dev\") also rewrites the dep pin to the running version"):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(Map.empty.get, home, "dev")
      val lines = os.read(dir / "implement.sc").linesIterator.toList
      val depLineIdx = lines.indexWhere(_.startsWith("//> using dep "))
      assertEquals(
        lines(depLineIdx),
        """//> using dep "org.virtuslab::orca:dev""""
      )
      assertEquals(lines(depLineIdx + 1), "//> using repository ivy2Local")
