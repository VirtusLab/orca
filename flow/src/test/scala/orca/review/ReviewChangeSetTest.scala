package orca.review

import orca.{FlowContext, FlowControl, InStage, TestFlowControl, stage}
import orca.plan.Title
import orca.events.EventDispatcher
import orca.testkit.TextReplyingAgent
import ox.either.orThrow

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

  private def bug(title: String): ReviewIssue =
    ReviewIssue(
      severity = Severity.Warning,
      confidence = Confidence(1.0).orThrow,
      title = Title(title),
      description = title,
      location = None,
      suggestion = None
    )

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
        task = "build the widget",
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
      outputs = List(ReviewResult(List(bug("real bug"))), ReviewResult.empty)
    )
    val late = new FakeAgent("late", outputs = List(ReviewResult.empty))
    val coder = new FakeAgent(
      "coder",
      outputs = List(FixOutcome(List(Title("real bug")), Nil)),
      onRun = () => commit(dir, "fixed.scala", "object Fixed")
    )
    val lateJoiner = new ReviewerSelector:
      def prepare(
          all: List[RosterEntry[?]],
          taskTitle: Title,
          changedFiles: List[String]
      )(using FlowContext, InStage) =
        history =>
          if history.isEmpty then all.filter(_.name == "early") else all
    given FlowControl = ctx
    stage("implement the widget"):
      val _ = reviewAndFixLoop(
        coderSession = ReviewLoopFixture.coderSession(coder),
        reviewers = List(early, late),
        task = "build the widget",
        reviewerSelection = lateJoiner
      )
    val prompt = firstPromptOf(late)
    assert(prompt.contains("fixed.scala"), prompt)

  test("a resumed reviewer sees an edit the fixer committed"):
    val (ctx, dir) = stagingControl()
    // The reviewer runs both rounds, so round two resumes its session — and its
    // own `git diff HEAD` is empty once the fixer commits.
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(bug("real bug"))), ReviewResult.empty)
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
        task = "build the widget",
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(resumePrompt.contains("fixed.scala"), resumePrompt)

  test("a pinned diff is not re-sent to a resumed reviewer as a fresh sample"):
    val (ctx, _) = stagingControl()
    // `initialDiff` pins one constant for the whole loop, so round two's sample
    // is byte-identical to round one's. Re-sending it would claim the fixer's
    // edits are inside a diff that predates them. The pinned diff is past the
    // inline threshold on purpose: equality is tested before size, so a pinned
    // diff never reaches the path-listing branch either, which is what lets
    // that branch take its paths from git.
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(bug("real bug"))), ReviewResult.empty)
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
        task = "build the widget",
        reviewerSelection = ReviewerSelector.allEveryRound,
        initialDiff = Some(
          "+++ b/pinned.scala\n" + (1 to 3000)
            .map(i => s"+// line $i")
            .mkString("\n")
        )
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(!resumePrompt.contains("pinned.scala"), resumePrompt)

  test("a change set too large to inline reaches a resumed reviewer as paths"):
    val (ctx, dir) = stagingControl()
    // Past the inline threshold the reviewer gets paths and opens the files
    // itself, so a resumed conversation doesn't accumulate one copy of a large
    // diff per round.
    val big = (1 to 3000).map(i => s"// line $i").mkString("\n")
    val reviewer = new FakeAgent(
      "r",
      outputs = List(ReviewResult(List(bug("real bug"))), ReviewResult.empty)
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
        task = "build the widget",
        reviewerSelection = ReviewerSelector.allEveryRound
      )
    val resumePrompt = reviewer.seenPrompts
      .lift(1)
      .getOrElse(fail("the reviewer ran once; no resume happened"))
    assert(resumePrompt.contains("big.scala"), resumePrompt)
    assert(!resumePrompt.contains("// line 2999"), resumePrompt)

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
        task = "build the widget",
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
    // `.distinct` because the rename is named twice: its header reads
    // `a/old.scala b/new.scala`, which `BoundedDiff.isShown` matches as a whole
    // line and so reports as not shown, the safe direction.
    assertEquals(
      (shown ++ notShown).distinct.sorted,
      List("gone.scala", "logo.png", "new.scala", "zz-big.scala")
    )
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
          all: List[RosterEntry[?]],
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
        task = "build the widget",
        reviewerSelection = recording
      )
    assertEquals(seen.get(), List("widget.scala"))
