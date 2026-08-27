package orca.review

import orca.{FlowContext, FlowControl, InStage, TestFlowControl, stage}
import orca.plan.Title
import orca.events.EventDispatcher
import orca.testkit.TextReplyingAgent

/** What `reviewAndFixLoop` shows its reviewers: the change set the enclosing
  * stage has produced, whether or not the agent that produced it committed
  * along the way (ADR 0011). Against `HEAD` alone a committed change reads as
  * an empty diff.
  */
class ReviewChangeSetTest extends munit.FunSuite:

  /** Stages commit, and the message is drafted by the coding role's cheap
    * model, so every control here needs a lead that answers.
    */
  private def stagingControl(): (TestFlowControl, os.Path) =
    TestFlowControl.create(
      new EventDispatcher(Nil),
      lead = Some(TextReplyingAgent("stage commit message"))
    )

  private def commit(dir: os.Path, name: String, content: String): Unit =
    os.write(dir / name, content)
    commitAll(dir, s"add $name")

  private def commitAll(dir: os.Path, message: String): Unit =
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", message).call(cwd = dir)

  /** A source line long enough that a few thousand of them pass the cap. */
  private def bigLine(i: Int): String =
    s"// a comment line, long enough to weigh something, number $i"

  private def firstPromptOf(agent: FakeAgent): String =
    agent.seenPrompts.headOption
      .getOrElse(fail(s"${agent.name} was never called"))

  test("a reviewer sees work the coding agent committed inside the stage"):
    val (ctx, dir) = stagingControl()
    val reviewer = new FakeAgent("r", outputs = List(ReviewResult.empty))
    given FlowControl = ctx
    stage("implement the widget"):
      commit(dir, "widget.scala", "object Widget")
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val prompt = firstPromptOf(reviewer)
    assert(prompt.contains("widget.scala"), prompt)

  test("a reviewer joining a later round sees an edit the fixer committed"):
    val (ctx, dir) = stagingControl()
    // Round one runs `early` alone; its issue triggers a fix turn that commits.
    // Round two admits `late`, whose first prompt must carry that commit.
    val early = new FakeAgent(
      "early",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val late = new FakeAgent("late", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => commit(dir, "fixed.scala", "object Fixed")
    )
    val lateJoiner = selector: (all, history) =>
      if history.isEmpty then all.filter(_.name == "early") else all
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(early, late),
        task = titled("build the widget"),
        reviewerSelection = lateJoiner
      )
    val prompt = firstPromptOf(late)
    assert(prompt.contains("fixed.scala"), prompt)

  test("a resumed reviewer sees an edit the fixer committed"):
    val (ctx, dir) = stagingControl()
    // The reviewer runs both rounds, so round two resumes its session — and its
    // own `git diff HEAD` is empty once the fixer commits. Nothing is committed
    // before the loop, so round one's sample is empty and round two's is not.
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => commit(dir, "fixed.scala", "object Fixed")
    )
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(resumePrompt.contains("fixed.scala"), resumePrompt)

  test("a pinned diff is not re-sent to a resumed reviewer as a fresh sample"):
    val (ctx, _) = stagingControl()
    // `ReviewDiff.Pinned` is one constant for the whole loop, so round two's
    // sample is byte-identical to round one's. Re-sending it would claim the
    // fixer's edits are inside a diff that predates them. The pinned diff is
    // past the inline threshold on purpose: equality is tested before size, so
    // a pinned diff never reaches the path-listing branch either, which is what
    // lets that branch take its paths from git.
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil))
    )
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound,
        diff = ReviewDiff.Pinned(
          "+++ b/pinned.scala\n" + (1 to 3000)
            .map(i => s"+// line $i")
            .mkString("\n")
        )
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(!resumePrompt.contains("pinned.scala"), resumePrompt)

  test("after an empty-sample round an unchanged sample says so again"):
    val (ctx, _) = stagingControl()
    // Nothing could be sampled in round one, so all the reviewer holds is the
    // placeholder note saying so. Round two samples the same nothing: pointing
    // it back at the diff in its conversation would name a diff it was never
    // sent.
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil))
    )
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound,
        diff = ReviewDiff.Pinned("")
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(
      resumePrompt.contains(
        "No change set could be sampled this round either. Do not conclude " +
          "that nothing changed — check the code the task describes to see " +
          "whether your earlier findings still stand."
      ),
      resumePrompt
    )
    assert(
      !resumePrompt.contains("the diff already in this conversation"),
      resumePrompt
    )

  test("a single file too large for the budget is named but not shown"):
    val (ctx, dir) = stagingControl()
    // Past the inline threshold the reviewer gets the changed files' sections
    // cut to that threshold, so a resumed conversation accumulates at most that
    // much per round. One file past it on its own leaves room for no section at
    // all, which degrades to naming the files.
    val big = (1 to 3000).map(i => s"// line $i").mkString("\n")
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => commit(dir, "big.scala", big)
    )
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(resumePrompt.contains("- big.scala"), resumePrompt)
    assert(resumePrompt.contains("read them directly"), resumePrompt)
    assert(!resumePrompt.contains("// line 2999"), resumePrompt)

  /** A minimal per-file diff section, padded to `pad` filler lines so a sample
    * can be pushed past the inline threshold.
    */
  private def diffSection(path: String, marker: String, pad: Int = 0): String =
    s"diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n" +
      s"@@ -1 +1 @@\n+$marker\n" + ("+filler\n" * pad)

  test("a too-large re-sample sends only the sections that changed"):
    // Under a whole-run diff the delta since a reviewer's last look is
    // typically one fix, so that is what it is sent — not the run's whole file
    // list to re-read, and not the whole diff again.
    val unchangedFile = diffSection("a.scala", "one", 4000)
    val previous =
      LastSent.Inline(unchangedFile + diffSection("b.scala", "two"))
    val current = DiffSample(
      unchangedFile + diffSection("b.scala", "three"),
      List("a.scala", "b.scala")
    )
    ReReviewChanges.of(previous, current) match
      case ReReviewChanges.Sections(sections, changed, unchanged) =>
        assertEquals(changed, List("b.scala"))
        assertEquals(unchanged, List("a.scala"))
        assert(sections.contains("+three"), sections)
        assert(!sections.contains("+one"), sections)
      case other => fail(s"expected bounded sections, got $other")

  test("a too-large re-sample with no previous diff names every path"):
    // Nothing to compare against — the reviewer's last round could sample
    // nothing — so every path counts as changed, marked as such by the empty
    // unchanged list.
    val current = DiffSample(
      diffSection("a.scala", "one", 1200) + diffSection("b.scala", "two", 1200),
      List("a.scala", "b.scala")
    )
    ReReviewChanges.of(LastSent.NoteOnly(""), current) match
      case ReReviewChanges.Sections(_, changed, unchanged) =>
        assertEquals(changed, List("a.scala", "b.scala"))
        assertEquals(unchanged, Nil)
      case other => fail(s"expected bounded sections, got $other")

  test("a too-large re-sample whose delta names no file has no sections"):
    // The samples differ outside every parseable section — here in the
    // preamble a cut sample carries — so there is nothing to cut sections
    // from, and the reviewer is pointed at the files instead.
    val sections = diffSection("a.scala", "one", 4000)
    val previous = LastSent.Inline("# skipped 1 file\n" + sections)
    val current =
      DiffSample("# skipped 2 files\n" + sections, List("a.scala"))
    assertEquals(
      ReReviewChanges.of(previous, current),
      ReReviewChanges.Paths(List("a.scala"))
    )

  test("the no-sections prompt tells the reviewer to read the files"):
    val prompt = ReviewLoopPrompts.reReview(
      ReReviewChanges.Paths(List("a.scala")),
      declined = Nil
    )
    assert(prompt.contains("- a.scala"), prompt)
    assert(prompt.contains("read them directly"), prompt)

  test("the whole-delta prompt offers the sections as all that fits"):
    // Nothing counts as unchanged, so the arm below's closing list would name
    // no file; this arm says what the payload is instead.
    val prompt = ReviewLoopPrompts.reReview(
      ReReviewChanges.Sections(
        diffSection("b.scala", "three"),
        List("b.scala"),
        Nil
      ),
      declined = Nil
    )
    assert(prompt.contains("as much of it as fits"), prompt)
    assert(!prompt.contains("unchanged since your previous round"), prompt)

  test("the delta prompt sends the sections and names the rest as unchanged"):
    val prompt = ReviewLoopPrompts.reReview(
      ReReviewChanges.Sections(
        diffSection("b.scala", "three"),
        List("b.scala"),
        List("a.scala")
      ),
      declined = Nil
    )
    assert(prompt.contains("+three"), prompt)
    assert(prompt.contains("- a.scala"), prompt)
    assert(
      prompt.contains("unchanged since your previous round"),
      prompt
    )

  test("a too-large delta reaches a resumed reviewer as the fix's sections"):
    // End to end: round two's change set is past the inline threshold, but the
    // only difference from what the reviewer saw in round one is the fixer's
    // one file — so that file's diff is what the resumed prompt carries, and
    // the rest of the change set is named as unchanged rather than re-sent.
    val (ctx, dir) = stagingControl()
    val big = (1 to 3000).map(i => s"// line $i").mkString("\n")
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(issue("real bug"))), ReviewResult.empty)
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => commit(dir, "fix.scala", "object Fix")
    )
    given FlowControl = ctx
    stage("implement the widget"):
      commit(dir, "big.scala", big)
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(resumePrompt.contains("+object Fix"), resumePrompt)
    assert(!resumePrompt.contains("// line 2999"), resumePrompt)
    assert(
      resumePrompt.contains("unchanged since your previous round"),
      resumePrompt
    )

  /** A reviewer that reports the same finding twice, then goes quiet, and a
    * fixer that claims a fix but only ever touches the tree once — so round
    * three re-samples exactly what round two did.
    */
  private def repeatUntilUnchanged(
      dir: os.Path,
      file: String,
      content: String
  ): (FakeAgent, FakeAgent) =
    val reviewer = new FakeAgent(
      "r",
      outputs = List(
        ReviewResult(List(issue("real bug"))),
        ReviewResult(List(issue("real bug"))),
        ReviewResult.empty
      )
    )
    val coder = new FakeAgent(
      "coder",
      outputs = List.fill(2)(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => if !os.exists(dir / file) then commit(dir, file, content)
    )
    (reviewer, coder)

  test("after a sections round an unchanged sample points at the sections"):
    val (ctx, dir) = stagingControl()
    // Round two's change set is past the inline threshold, and the fixer's one
    // file fits, so sections reach the reviewer. Round three re-samples the
    // same bytes, and must point it back at what it holds rather than at a
    // diff it was never sent.
    val big = (1 to 3000).map(i => s"// line $i").mkString("\n")
    val (reviewer, coder) =
      repeatUntilUnchanged(dir, "fix.scala", "object Fix")
    given FlowControl = ctx
    stage("implement the widget"):
      commit(dir, "big.scala", big)
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val lastPrompt = reviewer.seenPrompts
      .lift(2)
      .getOrElse(fail("the reviewer ran fewer than three rounds"))
    assert(
      lastPrompt.contains("the diff sections already in this conversation"),
      lastPrompt
    )

  test("after a no-sections round an unchanged sample points at the file list"):
    val (ctx, dir) = stagingControl()
    // The other half of the arm above: round two's one file is too large for
    // any section to fit, so all the reviewer holds is a file list — and that
    // is what an unchanged round three must point it at.
    val big = (1 to 3000).map(i => s"// line $i").mkString("\n")
    val (reviewer, coder) = repeatUntilUnchanged(dir, "big.scala", big)
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val lastPrompt = reviewer.seenPrompts
      .lift(2)
      .getOrElse(fail("the reviewer ran fewer than three rounds"))
    assert(
      lastPrompt.contains("the file list already in this conversation"),
      lastPrompt
    )

  test("an initial diff past the cap still names every file it leaves out"):
    // The cap's whole point: whatever the change set's size, the reviewer's
    // first prompt covers all of it — the files it shows plus the files it
    // names as not shown. A rename, a deletion and a binary change are in the
    // fixture because none of them is visible in a diff body the way a plain
    // edit is.
    val (ctx, dir) = stagingControl()
    os.write(dir / "old.scala", "object Old")
    os.write(dir / "gone.scala", "object Gone")
    os.write(dir / "logo.png", Array[Byte](0, 1, 2, 3))
    commitAll(dir, "seed")
    val reviewer = new FakeAgent("r", outputs = List(ReviewResult.empty))
    given FlowControl = ctx
    stage("implement the widget"):
      os.move(dir / "old.scala", dir / "new.scala")
      val _ = os.remove(dir / "gone.scala")
      os.write.over(dir / "logo.png", Array[Byte](4, 5, 6, 7))
      // Sorts last, so the files before it are shown whole and it is the one
      // the cap pushes into the trailer.
      os.write(dir / "zz-big.scala", (1 to 4000).map(bigLine).mkString("\n"))
      commitAll(dir, "the work")
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
        reviewers = List(reviewer),
        task = titled("build the widget"),
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val prompt = firstPromptOf(reviewer)
    val shown = prompt.linesIterator
      .filter(_.startsWith("diff --git "))
      .map(_.split(" b/").last)
      .toList
    val notShown = prompt.linesIterator
      .filter(_.startsWith("#   "))
      .map(_.drop(4).takeWhile(_ != ' '))
      .toList
    assertEquals(
      (shown ++ notShown).distinct.sorted,
      List("gone.scala", "logo.png", "new.scala", "zz-big.scala")
    )
    // The rename is the one file named twice: its header reads
    // `a/old.scala b/new.scala`, which `BoundedDiff.isShown` compares as a
    // whole line and so reports as not shown — the safe direction, telling the
    // reviewer to open a file it has already seen.
    assertEquals(shown.toSet.intersect(notShown.toSet), Set("new.scala"))
    assert(notShown.contains("zz-big.scala"), prompt.takeRight(500))

  test("reviewer selection sees the files of work the agent committed"):
    // The defect's other half: an empty change set means the file-pattern
    // pre-filter matches nothing, dropping every file-gated reviewer before the
    // picker is even asked.
    val (ctx, dir) = stagingControl()
    val seen =
      new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val recording = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, InStage) =
        seen.set(changedFiles)
        _ => all
    given FlowControl = ctx
    stage("implement the widget"):
      commit(dir, "widget.scala", "object Widget")
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(new FakeAgent("coder")),
        reviewers =
          List(new FakeAgent("r", outputs = List(ReviewResult.empty))),
        task = titled("build the widget"),
        reviewerSelection = recording
      )
    assertEquals(seen.get(), List("widget.scala"))
