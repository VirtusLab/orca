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
    while i < text.length && (i == from || depth > 0) do
      text(i) match
        case '(' => depth += 1
        case ')' => depth -= 1
        case _   => ()
      i += 1
    assert(
      depth == 0,
      s"$name's reviewAndFixLoop call never closes — the scan counts every " +
        "paren, a string's or a comment's included"
    )
    text.substring(open, i)

  test("a flow's final review reviews the whole run"):
    // `diff = ReviewDiff.WholeRun` is what makes the stage a whole-run review
    // rather than an empty stage-scoped one — a "Final review" stage without
    // it reviews nothing. Pinned inside the final-review call itself, so it
    // can't be satisfied by another call in the file.
    taskBasedFlows.foreach: name =>
      val call = finalReviewCall(name)
      assert(call.contains("diff = ReviewDiff.WholeRun"), s"$name: $call")

  /** The cap every whole-run final review states. Above `DefaultMaxIterations`
    * because nothing reviews again after that loop: what it leaves open ships,
    * listed in the PR.
    */
  private val FinalReviewCap: Int = 5

  private val statedCap = "maxIterations\\s*=\\s*(\\d+)".r

  test("the final-review cap exceeds the library default"):
    assert(
      FinalReviewCap > DefaultMaxIterations,
      s"the final-review cap ($FinalReviewCap) must exceed the default " +
        s"($DefaultMaxIterations): nothing reviews again after that loop"
    )

  test("every final review states the final-review cap"):
    // Pinned inside the final-review call itself, so a cap stated elsewhere in
    // the file can't satisfy it.
    taskBasedFlows.foreach: name =>
      val stated =
        statedCap.findAllMatchIn(finalReviewCall(name)).map(_.group(1)).toList
      assertEquals(stated, List(FinalReviewCap.toString), name)

  test("every cap outside a final review is the library default, spelled out"):
    // Each flow writes the number instead of inheriting it, so raising
    // `DefaultMaxIterations` fails here until the flows follow.
    val outside = indexNames
      .map: name =>
        val text = resourceText(name)
        val rest =
          if taskBasedFlows.contains(name) then
            text.replace(finalReviewCall(name), "")
          else text
        name -> statedCap.findAllMatchIn(rest).map(_.group(1)).toList
      .filter((_, stated) => stated.nonEmpty)
    assert(outside.nonEmpty, "no flow states a cap outside a final review")
    outside.foreach: (name, stated) =>
      assertEquals(stated.distinct, List(DefaultMaxIterations.toString), name)

  /** A flow's last statement: from the last line where one starts — flow-body
    * indentation, opening with a name — to the end, so a call spread over
    * several lines is whole, its own closing paren included.
    */
  private def lastStatement(name: String): String =
    val fromEnd =
      resourceText(name).linesIterator.toList.reverse.dropWhile(_.trim.isEmpty)
    val (deeper, rest) = fromEnd.span(!_.matches("  [A-Za-z].*"))
    (rest.headOption.toList ++ deeper.reverse).mkString("\n")

  /** The flows that finish with [[orca.pr.openPrIfGitHub]]. */
  private val bestEffortPrFlows = List(
    "implement-enhanced.sc",
    "implement-interactive.sc",
    "implement.sc",
    "simple.sc"
  )

  /** The flows that finish with [[orca.pr.openPrFromBranch]]. */
  private val requiredPrFlows = List("issue-pr.sc")

  /** The flows that open their PR with a bare `gh.createPr`: no helper writes
    * the body or records the handle, so each does both itself. Derived, so a
    * second such flow is covered by the rules below rather than skipped.
    */
  private def ownBodyPrFlows: List[String] =
    indexNames.filter(resourceText(_).contains("gh.createPr(")).sorted

  test("every code-producing flow takes the best-effort PR step"):
    // Three exact sets that partition the flows by how they open their PR, so a
    // code-producing flow that drops the step — or reaches for the
    // GitHub-requiring one — shows up here, and so does a `review.sc` that
    // grows a PR step it should not have.
    assertEquals(
      indexNames.filter(resourceText(_).contains("openPrIfGitHub(")).sorted,
      bestEffortPrFlows
    )
    assertEquals(
      indexNames.filter(resourceText(_).contains("openPrFromBranch(")).sorted,
      requiredPrFlows
    )
    assertEquals(ownBodyPrFlows, List("issue-pr-bugfix.sc"))

  test("the best-effort PR step is each flow's last statement"):
    // "Ends with", not merely "calls": the step pushes the branch and describes
    // it from the whole branch diff, so a call placed above the final review
    // would summarise work the review then keeps changing.
    bestEffortPrFlows.foreach: name =>
      assert(
        lastStatement(name).matches("(?s)\\s*(?:val _ = )?openPrIfGitHub\\(.*"),
        s"$name ends with: ${lastStatement(name)}"
      )

  test("a flow that opens its own PR records it for the lifecycle"):
    // A bare `gh.createPr` hands the flow the handle, so it only reaches the
    // lifecycle — and the run only ends on the start branch — if the flow
    // records it itself.
    ownBodyPrFlows.foreach: name =>
      assert(resourceText(name).contains("recordOpenedPr("), name)

  test("every flow that opens a PR hands it what its review left open"):
    // The whole point of the required `openFindings` parameter: a flow that
    // drops it opens a PR saying nothing about the findings it shipped. The
    // argument must be the review stage's own value — `IgnoredIssues(Nil)`
    // satisfies the compiler and is exactly the regression to catch.
    (bestEffortPrFlows ++ requiredPrFlows).foreach: name =>
      val text = resourceText(name)
      assert(text.contains("openFindings = openFindings"), name)
      assert(text.contains("val openFindings = stage("), name)
    // A flow writing its own body appends the section itself.
    ownBodyPrFlows.foreach: name =>
      assert(resourceText(name).contains("bodyWithOpenFindings("), name)

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
