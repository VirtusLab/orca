package orca.pr

import munit.FunSuite
import orca.{BoundedDiff, OrcaFlowException, OutsideStage, interceptReported}
import orca.plan.Title
import orca.progress.PublishedWork
import orca.review.{FindingId, OpenFinding, OpenFindings, OpenReason}
import orca.tools.{
  BranchNotPushed,
  GitHubAvailability,
  PrCreateFailed,
  PrHandle,
  PushFailure
}
import orca.events.{OrcaEvent, OrcaListener}

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrFromBranch]] — push → summarise → create as three
  * resume-safe stages. Pins the structure: three stages in fixed order, with
  * the resume-critical property that `git.push` runs in an earlier stage than
  * `gh.createPr`. Recording `git`/`gh` doubles capture call order; a real
  * [[orca.TestFlowControl]] runs the actual `stage` machinery so the emitted
  * stage boundaries are real. Also pins that the branch diff reaches the
  * summariser bounded; how it is cut is [[orca.BoundedDiffTest]]. Open findings
  * reach the body as a section; what the section says is
  * [[BodyWithOpenFindingsTest]].
  */
class OpenPrFromBranchTest extends FunSuite:

  /** What one flow run against a branch diff of `branchDiff` produced. */
  private case class Run(
      handle: PrHandle,
      calls: List[String],
      stages: List[String],
      prompt: String,
      published: Option[PublishedWork],
      prBody: String,
      steps: List[String]
  )

  private def run(
      branchDiff: String,
      openFindings: OpenFindings = OpenFindings.empty,
      context: Option[String] = None
  ): Run =
    val (dir, store) = seededPrRepo()
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val bodies = new ConcurrentLinkedQueue[String]()
    val steps = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(path) => stages.add(path.name): Unit
      case OrcaEvent.Step(message)      => steps.add(message): Unit
      case _                            => ()

    val summariser = new StubSummariser()
    val control =
      prControl(dir, store, listener, calls, branchDiff, prBodies = bodies)
    val handle = openPrFromBranch(
      summarisingAgent = summariser,
      openFindings = openFindings,
      body = summary => s"${summary.body}\n\nCloses #1.",
      context = context
    )(using control.context, control, summon[OutsideStage])
    Run(
      handle,
      calls.asScala.toList,
      stages.asScala.toList,
      summariser.captured,
      store.load().flatMap(_.published),
      bodies.asScala.toList.headOption.getOrElse(fail("createPr never ran")),
      steps.asScala.toList
    )

  private val oneOpen = OpenFindings(
    List(
      OpenFinding(
        FindingId("R1.I1.1"),
        Title("Null check missing"),
        OpenReason.CapReached(3),
        None
      )
    ),
    skipped = None
  )

  test("openPrFromBranch runs push, summarise, create as three ordered stages"):
    val r = run("stub-diff")
    assertEquals(r.handle, samplePr)
    assertEquals(
      r.published,
      Some(PublishedWork(samplePr.url)),
      "the PR was not recorded"
    )
    // Push before PR: the resume-critical stage split.
    assertEquals(r.calls, List("push", "createPr"))
    assertEquals(
      r.stages,
      List("Push branch", "Generate PR title and description", "Open PR")
    )

  test("openPrFromBranch reached inside a stage is refused before it pushes"):
    // A helper taking only FlowControl carries OutsideStage past the compile
    // check; the stage open around it is caught at run time.
    val (dir, store) = seededPrRepo()
    val calls = new ConcurrentLinkedQueue[String]()
    val control = prControl(dir, store, _ => (), calls)
    val e = intercept[OrcaFlowException](
      control.withStage("outer", None): _ =>
        openPrFromBranch(
          summarisingAgent = new StubSummariser(),
          openFindings = OpenFindings.empty
        )(using control.context, control, summon[OutsideStage])
    )
    assert(e.getMessage.contains("inside stage 'outer#0'"), e.getMessage)
    assertEquals(calls.asScala.toList, Nil)

  test("openPrFromBranch throws when the PR cannot be opened"):
    // The contract its best-effort sibling deliberately does not share: the
    // finding flows exist to open a PR, so a refusal must fail the run.
    val (dir, store) = seededPrRepo()
    val control = prControl(
      dir,
      store,
      _ => (),
      new ConcurrentLinkedQueue[String](),
      createPr = Left(new BranchNotPushed)
    )
    val _ = interceptReported[PrCreateFailed](
      openPrFromBranch(
        summarisingAgent = new StubSummariser(),
        openFindings = OpenFindings.empty
      )(using
        control.context,
        control,
        summon[OutsideStage]
      )
    )

  test("open findings are printed when opening the PR fails"):
    val (dir, store) = seededPrRepo()
    val steps = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.Step(message) => steps.add(message): Unit
      case _                       => ()
    val control = prControl(
      dir,
      store,
      listener,
      new ConcurrentLinkedQueue[String](),
      createPr = Left(new BranchNotPushed)
    )
    val _ = interceptReported[PrCreateFailed](
      openPrFromBranch(
        summarisingAgent = new StubSummariser(),
        openFindings = oneOpen
      )(using control.context, control, summon[OutsideStage])
    )
    assert(steps.contains(openFindingsSection(oneOpen).get), steps)

  test("a resumed run hands back the replayed handle without re-running"):
    val (dir, store) = seededPrRepo()
    val summariser = new StubSummariser()
    def attempt(calls: ConcurrentLinkedQueue[String]): PrHandle =
      val control = prControl(dir, store, _ => (), calls)
      openPrFromBranch(
        summarisingAgent = summariser,
        openFindings = OpenFindings.empty
      )(using control.context, control, summon[OutsideStage])

    val _ = attempt(new ConcurrentLinkedQueue[String]())
    val resumedCalls = new ConcurrentLinkedQueue[String]()
    val resumed = attempt(resumedCalls)
    assertEquals(resumedCalls.asScala.toList, Nil, "stages were re-run")
    assertEquals(resumed, samplePr)
    // The record the first attempt wrote is what teardown reads, so it has to
    // outlive the resume that replays the stage.
    assertEquals(
      store.load().flatMap(_.published),
      Some(PublishedWork(samplePr.url))
    )

  test("a resume over a push refusal openPrIfGitHub recorded fails the run"):
    val (dir, store) = seededPrRepo()
    val first = prControl(
      dir,
      store,
      _ => (),
      new ConcurrentLinkedQueue[String](),
      availability = GitHubAvailability.Available("github.com", "acme", "w"),
      push = Left(new PushFailure.RemoteDeclined("protected branch"))
    )
    val _ = openPrIfGitHub(
      summarisingAgent = new StubSummariser(),
      openFindings = OpenFindings.empty
    )(using first.context, first, summon[OutsideStage])
    val calls = new ConcurrentLinkedQueue[String]()
    val resumed = prControl(dir, store, _ => (), calls)
    val e = interceptReported[OrcaFlowException](
      openPrFromBranch(
        summarisingAgent = new StubSummariser(),
        openFindings = OpenFindings.empty
      )(using resumed.context, resumed, summon[OutsideStage])
    )
    assert(e.getMessage.contains("orca will not retry"), e.getMessage)
    assertEquals(calls.asScala.toList, Nil, "the push was re-run")

  test("open findings follow the flow's body as their own section"):
    val open = OpenFindings(
      List(
        OpenFinding(
          FindingId("R1.I1.1"),
          Title("Null check missing"),
          OpenReason.CapReached(3),
          None
        )
      ),
      skipped = None
    )
    val body = run("stub-diff", open).prBody
    assert(
      body.startsWith(
        "Generated body\n\nCloses #1.\n\n## Open review findings"
      ),
      body
    )

  test("open findings are printed when the PR is opened"):
    val steps = run("stub-diff", oneOpen).steps
    assert(steps.contains(openFindingsSection(oneOpen).get), steps)

  test(
    "without a context the summariser gets the user prompt and closes its issues"
  ):
    val prompt = run("stub-diff").prompt
    assert(prompt.contains("User prompt: p"), prompt)
    assert(prompt.contains(PrPrompts.ClosingRefs), prompt)

  test("with a context the summariser is not asked for closing lines"):
    val prompt =
      run("stub-diff", context = Some("Originating issue: a/b#1")).prompt
    assert(prompt.contains("Originating issue: a/b#1"), prompt)
    assert(!prompt.contains("User prompt:"), prompt)
    assert(!prompt.contains(PrPrompts.ClosingRefs), prompt)

  test("with nothing open the body is the flow's own, nothing appended"):
    assertEquals(run("stub-diff").prBody, "Generated body\n\nCloses #1.")

  test("a branch too large to summarise reaches the agent cut short"):
    // Unbounded, this is the prompt no context window takes, and it is rebuilt
    // on every re-run — the push stage has already committed by then.
    val diff = "+" * (BoundedDiff.ReviewThreshold * 2)
    val r = run(diff)
    assert(!r.prompt.contains(diff), "the whole branch diff was sent")
    assert(r.prompt.contains("[diff cut at "), "the cut went unmarked")
