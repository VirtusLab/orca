package orca.pr

import munit.FunSuite
import orca.{BoundedDiff, OutsideStage}
import orca.tools.{BranchNotPushed, PrCreateFailed, PrHandle}
import orca.events.{OrcaEvent, OrcaListener}

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrFromBranch]] — push → summarise → create as three
  * resume-safe stages. Pins the structure: three stages in fixed order, with
  * the resume-critical property that `git.push` runs in an earlier stage than
  * `gh.createPr`. Recording `git`/`gh` doubles capture call order; a real
  * [[orca.TestFlowControl]] runs the actual `stage` machinery so the emitted
  * stage boundaries are real. Also pins that the branch diff reaches the
  * summariser bounded; how it is cut is [[orca.BoundedDiffTest]].
  */
class OpenPrFromBranchTest extends FunSuite:

  /** What one flow run against a branch diff of `branchDiff` produced. */
  private case class Run(
      handle: PrHandle,
      calls: List[String],
      stages: List[String],
      prompt: String,
      openedPr: Option[PrHandle]
  )

  private def run(branchDiff: String): Run =
    val (dir, store) = seededPrRepo()
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(name) => stages.add(name): Unit
      case _                            => ()

    val summariser = new StubSummariser()
    val control = prControl(dir, store, listener, calls, branchDiff)
    val handle = openPrFromBranch(
      summarisingAgent = summariser,
      body = summary => s"${summary.body}\n\nCloses #1."
    )(using control, control, summon[OutsideStage])
    Run(
      handle,
      calls.asScala.toList,
      stages.asScala.toList,
      summariser.captured,
      store.load().flatMap(_.openedPr)
    )

  test("openPrFromBranch runs push, summarise, create as three ordered stages"):
    val r = run("stub-diff")
    assertEquals(r.handle, samplePr)
    assertEquals(r.openedPr, Some(samplePr), "the PR was not recorded")
    // Push before PR: the resume-critical stage split.
    assertEquals(r.calls, List("push", "createPr"))
    assertEquals(
      r.stages,
      List("Push branch", "Generate PR title and description", "Open PR")
    )

  test("openPrFromBranch throws when the PR cannot be opened"):
    // The contract its best-effort sibling deliberately does not share: the
    // issue flows exist to open a PR, so a refusal must fail the run.
    val (dir, store) = seededPrRepo()
    val control = prControl(
      dir,
      store,
      _ => (),
      new ConcurrentLinkedQueue[String](),
      createPr = Left(new BranchNotPushed)
    )
    val _ = intercept[PrCreateFailed](
      openPrFromBranch(summarisingAgent = new StubSummariser())(using
        control,
        control,
        summon[OutsideStage]
      )
    )

  test("a resumed run hands back the replayed handle without re-running"):
    val (dir, store) = seededPrRepo()
    val summariser = new StubSummariser()
    def attempt(calls: ConcurrentLinkedQueue[String]): PrHandle =
      val control = prControl(dir, store, _ => (), calls)
      openPrFromBranch(summarisingAgent = summariser)(using
        control,
        control,
        summon[OutsideStage]
      )

    val _ = attempt(new ConcurrentLinkedQueue[String]())
    val resumedCalls = new ConcurrentLinkedQueue[String]()
    val resumed = attempt(resumedCalls)
    assertEquals(resumedCalls.asScala.toList, Nil, "stages were re-run")
    assertEquals(resumed, samplePr)
    // The record the first attempt wrote is what teardown reads, so it has to
    // outlive the resume that replays the stage.
    assertEquals(store.load().flatMap(_.openedPr), Some(samplePr))

  test("a branch too large to summarise reaches the agent cut short"):
    // Unbounded, this is the prompt no context window takes, and it is rebuilt
    // on every re-run — the push stage has already committed by then.
    val diff = "+" * (BoundedDiff.ReviewThreshold * 2)
    val r = run(diff)
    assert(!r.prompt.contains(diff), "the whole branch diff was sent")
    assert(r.prompt.contains("[diff cut at "), "the cut went unmarked")
