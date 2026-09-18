package orca.pr

import munit.FunSuite
import orca.tools.{
  BranchNotPushed,
  GitHubAvailability,
  GitHubUnavailable,
  NoDefaultBase,
  PrCreateFailed,
  PrHandle,
  PushFailure
}
import orca.{OutsideStage, WorkspaceWrite}
import orca.plan.Title
import orca.review.{OpenFinding, OpenFindings}
import orca.events.{OrcaEvent, OrcaListener}
import orca.progress.{
  BranchMode,
  ProgressLog,
  ProgressStore,
  PublishedWork,
  StageEntry
}

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrIfGitHub]]. Two shapes of skip: the probe refusing before
  * anything runs, where the run must be left untouched — no stage entered (one
  * would replay as "done" on resume), no `git.push`, no `gh.createPr`, no PR
  * recorded; and a leg refusing mid-way, where the stages have run and the
  * refusal must come back as a line and `None` rather than an exception.
  */
class OpenPrIfGitHubTest extends FunSuite:

  /** What one `openPrIfGitHub` run produced. */
  private case class Run(
      result: Option[PrHandle],
      calls: List[String],
      stages: List[String],
      steps: List[String],
      errors: List[String],
      published: Option[PublishedWork],
      prBodies: List[String]
  )

  private val available =
    GitHubAvailability.Available(
      host = "github.com",
      owner = "acme",
      repo = "widgets"
    )

  /** A base a test expects never to be resolved. */
  private def baseForced: Either[NoDefaultBase, String] =
    throw new IllegalStateException("defaultBase was forced")

  private def run(
      availability: GitHubAvailability,
      withCode: Boolean = true,
      branchMode: BranchMode = BranchMode.Created,
      startBranch: String = "main",
      createPr: => Either[PrCreateFailed, PrHandle] = Right(samplePr),
      push: => Either[PushFailure, Unit] = Right(()),
      base: => Either[NoDefaultBase, String] = Right("main"),
      store: ProgressStore => ProgressStore = identity,
      openFindings: OpenFindings = OpenFindings(Nil)
  ): Run =
    val (dir, seededStore) = seededPrRepo(withCode, branchMode, startBranch)
    runOver(
      dir,
      store(seededStore),
      availability,
      createPr,
      push,
      base,
      openFindings = openFindings
    )

  /** [[run]] over a repo and store the test prepared itself. `beforeRun` gets
    * the repo once the control is built, for a test that must break the repo
    * only after the fixture has read it.
    */
  private def runOver(
      dir: os.Path,
      store: ProgressStore,
      availability: GitHubAvailability,
      createPr: => Either[PrCreateFailed, PrHandle] = Right(samplePr),
      push: => Either[PushFailure, Unit] = Right(()),
      base: => Either[NoDefaultBase, String] = Right("main"),
      summariser: StubSummariser = new StubSummariser(),
      beforeRun: os.Path => Unit = _ => (),
      openFindings: OpenFindings = OpenFindings(Nil)
  ): Run =
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val steps = new ConcurrentLinkedQueue[String]()
    val errors = new ConcurrentLinkedQueue[String]()
    val bodies = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(name) => stages.add(name): Unit
      case OrcaEvent.Step(message)      => steps.add(message): Unit
      case OrcaEvent.Error(message, _)  => errors.add(message): Unit
      case _                            => ()

    val control = prControl(
      dir,
      store,
      listener,
      calls,
      availability = availability,
      createPr = createPr,
      push = push,
      base = base,
      prBodies = bodies
    )
    beforeRun(dir)
    val result = openPrIfGitHub(
      summarisingAgent = summariser,
      openFindings = openFindings
    )(using control, control, summon[OutsideStage])
    Run(
      result,
      calls.asScala.toList,
      stages.asScala.toList,
      steps.asScala.toList,
      errors.asScala.toList,
      store.load().flatMap(_.published),
      bodies.asScala.toList
    )

  /** Every skip leg leaves the run as it found it, so assert that once and let
    * each test add what its own answer should say.
    */
  private def assertSkipped(r: Run): Unit =
    assertEquals(r.result, None)
    assertEquals(r.stages, Nil)
    assertEquals(r.calls, List("availability"))
    assertEquals(r.published, None)
    assertEquals(r.steps.size, 1)
    assert(r.steps.head.contains("no PR opened"), r.steps.head)
    assertEquals(r.errors, Nil)

  test("openPrIfGitHub directly inside a stage body does not compile"):
    val errors = compileErrors(
      """
      given orca.FlowControl = ???
      given orca.InStage = orca.InStage.unsafe
      openPrIfGitHub(
        summarisingAgent = new StubSummariser(),
        openFindings = orca.review.OpenFindings(Nil)
      )
      """
    )
    assert(
      errors.contains("must be called outside a stage") &&
        errors.contains("openPrIfGitHub(...)"),
      s"expected the OutsideStage implicitNotFound message, got: $errors"
    )

  test(
    "when unavailable, no PR is opened and the line ends in the next action"
  ):
    val r = run(
      GitHubAvailability.Unavailable(GitHubUnavailable.NotGitHub("gitlab.com"))
    )
    assertSkipped(r)
    assertEquals(
      r.steps.head,
      s"${GitHubUnavailable.NotGitHub("gitlab.com").explanation}, no PR " +
        "opened — push the branch and open the PR yourself"
    )

  test("on GitHub, the probe runs first and the PR is opened"):
    val r = run(available)
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.published, Some(PublishedWork(samplePr.url)))
    assertEquals(r.calls, List("availability", "push", "createPr"))
    // gh resolves the base repo from the checkout's remotes, so where the PR
    // lands is named before the push rather than only in the resulting URL.
    assert(
      r.steps.head.contains("Opening a PR on github.com/acme/widgets"),
      r.steps
    )

  test("open findings reach the body of the PR this step opens"):
    // The step every code-producing built-in flow ends with, so the section
    // has to survive the best-effort path too, not only openPrFromBranch's.
    val open = OpenFindings(
      List(OpenFinding(Title("Null check missing"), "max iterations reached"))
    )
    assertEquals(
      run(available, openFindings = open).prBodies,
      List(bodyWithOpenFindings("Generated body", open))
    )

  test("a run on a reused branch opens its PR without the no-code check"):
    // Under --skip-branch the header's starting branch IS the run's branch, so
    // a diff between the two would say "no code" whatever the run committed.
    // The lifecycle never treats a reused branch as throwaway either.
    val r = run(available, withCode = false, branchMode = BranchMode.Reused)
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))

  test("a run that changed no code opens no PR, measured from its start point"):
    // The branch carries only orca's progress log: a PR for it would be
    // empty, and teardown deletes a branch like that. Started from `develop`,
    // which is ahead of the default base `main` (`PushlessGit.defaultBase`) —
    // measuring against `main` would see develop's commit and open one.
    val r = run(available, withCode = false, startBranch = "develop")
    assertEquals(r.result, None)
    assertEquals(r.published, None)
    assertEquals(r.calls, List("availability"))
    assert(r.steps.last.contains("changed no code"), r.steps.last)
    // Nothing is announced for a PR that is not going to be opened.
    assert(!r.steps.exists(_.contains("Opening a PR on")), r.steps)

  test("a run whose header does not load gets its PR"):
    // The no-code check cannot measure such a run, and fails open: an unneeded
    // PR is cheaper than a lost one, and the lifecycle never deletes a branch
    // a PR was opened from.
    val r = run(available, withCode = false, store = new UnloadableHeader(_))
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))

  test("a run whose start branch git no longer has gets its PR"):
    // The header names a branch that is gone, so the no-code check cannot
    // diff against it: the same fail-open as an unreadable header, rather
    // than git's error ending the run.
    val (dir, store) = seededPrRepo(withCode = false)
    val r = runOver(
      dir,
      store,
      available,
      beforeRun =
        dir => os.proc("git", "branch", "-D", "main").call(cwd = dir): Unit
    )
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))
    assertEquals(r.errors, Nil)

  test("no resolvable base branch is reported before anything is pushed"):
    // Neither origin/HEAD nor origin/main nor origin/master resolves — an
    // environment answer, read before the push.
    val r = run(available, base = Left(new NoDefaultBase))
    assertEquals(r.result, None)
    assertEquals(r.calls, List("availability"))
    assert(r.steps.last.contains("base branch"), r.steps.last)
    assert(r.steps.last.contains("no PR opened"), r.steps.last)
    assert(
      r.steps.last.contains("run `git remote set-head origin -a`"),
      r.steps.last
    )
    // One remedy, this step's: NoDefaultBase's own is not spliced in.
    assert(!r.steps.last.contains("diffVsBase"), r.steps.last)

  test("a refused push is reported, and the create is never reached"):
    val r = run(
      available,
      push = Left(new PushFailure.RemoteDeclined("protected branch"))
    )
    assertEquals(r.result, None)
    assertEquals(r.published, None)
    assertEquals(r.calls, List("availability", "push"))
    assert(r.steps.last.contains("could not push the branch"), r.steps.last)
    assert(!r.steps.last.contains("will not retry"), r.steps.last)
    // The refusal is the stage's result, not its failure.
    assertEquals(r.errors, Nil)

  test("a create that throws outside orca's own exceptions is reported too"):
    // gh output the tool cannot parse (the already-exists lookup decodes JSON)
    // throws a plain runtime exception; best effort absorbs it like the rest.
    val r = run(
      available,
      createPr = throw new RuntimeException("unexpected end of input\nat 0x0")
    )
    assertEquals(r.result, None)
    assertEquals(r.published, None)
    assert(r.steps.last.contains("could not open a PR"), r.steps.last)
    assert(r.steps.last.contains("unexpected end of input"), r.steps.last)
    // Only the first line: the reason lands in one Step.
    assert(!r.steps.last.contains("\n"), r.steps.last)

  test("a refused PR creation is reported, not thrown"):
    // `createPr` models its refusals as values and `openPrFromBranch` throws
    // them; best effort turns them back into one line and a finished run.
    val r = run(available, createPr = Left(new BranchNotPushed))
    assertEquals(r.result, None)
    assertEquals(r.published, None)
    assert(r.steps.last.contains("could not open a PR"), r.steps.last)
    assert(r.steps.last.contains("no PR opened"), r.steps.last)
    // The refusal is the stage's result, not its failure: the user sees the
    // one line, no stage error.
    assertEquals(r.errors, Nil)

  test("a resume with every stage recorded reports the PR without a base"):
    // The first attempt opened the PR. On resume every stage replays, so
    // nothing needs the base branch — neither the probe nor git is asked, and
    // the PR that exists is reported again.
    val (dir, store) = seededPrRepo()
    val _ = runOver(dir, store, available)
    val r = runOver(dir, store, available, base = baseForced)
    assertEquals(r.calls, Nil, "stages were re-run")
    assertEquals(r.result, Some(samplePr))
    // The record the first attempt wrote is what teardown reads, so it has to
    // outlive the resume that replays the stage.
    assertEquals(r.published, Some(PublishedWork(samplePr.url)))

  test("a resume replays a recorded push refusal without asking the remote"):
    // The refusal is the push stage's result, so it replays like any other:
    // no probe, no push, and no base branch resolved for a summarise that is
    // never reached.
    val (dir, store) = seededPrRepo()
    val _ = runOver(
      dir,
      store,
      available,
      push = Left(new PushFailure.RemoteDeclined("protected branch"))
    )
    val r = runOver(dir, store, available, base = baseForced)
    assertEquals(r.calls, Nil)
    assertEquals(r.result, None)
    assertEquals(r.published, None)
    assert(r.steps.last.contains("could not push the branch"), r.steps.last)
    assert(r.steps.last.contains("orca will not retry"), r.steps.last)

  /** A first attempt whose push is recorded and whose summarise stage failed,
    * so a resume enters at the summarise. The `intercept` also pins that the
    * summarise stage is not wrapped.
    */
  private def pushedThenFailedSummarise(): (os.Path, ProgressStore) =
    val (dir, store) = seededPrRepo()
    val _ = intercept[IllegalStateException]:
      runOver(
        dir,
        store,
        available,
        summariser =
          new StubSummariser(throw new IllegalStateException("model down"))
      )
    (dir, store)

  test("a resume entering at the summarise resolves the base and opens the PR"):
    val (dir, store) = pushedThenFailedSummarise()
    val r = runOver(dir, store, available)
    assertEquals(r.calls, List("createPr"), "the push was re-run or probed")
    assertEquals(r.result, Some(samplePr))

  test("a resume entering at the summarise reports a base it cannot resolve"):
    val (dir, store) = pushedThenFailedSummarise()
    val r = runOver(dir, store, available, base = Left(new NoDefaultBase))
    assertEquals(r.calls, Nil)
    assertEquals(r.result, None)
    assert(r.steps.last.contains("base branch"), r.steps.last)
    assert(r.steps.last.contains("no PR opened"), r.steps.last)

  test("a failure of the stage machinery itself is not absorbed"):
    // Best effort covers the remote leg only. The progress record failing
    // after a push that went through is orca's own failure, and the run must
    // say so rather than report "could not push".
    val e = intercept[IllegalStateException]:
      run(available, store = new UnrecordableStages(_))
    assert(e.getMessage.contains("disk full"), e.getMessage)

  test("a create whose record cannot be written fails the run"):
    // The PR exists, but the log the lifecycle reads it from does not: that is
    // orca's own failure, outside the best-effort absorb around `gh.createPr`.
    val e = intercept[IllegalStateException]:
      run(available, store = new UnrecordablePr(_))
    assert(e.getMessage.contains("disk full"), e.getMessage)

  /** `underlying` as a run finds it when its header cannot be read. */
  private class UnloadableHeader(underlying: ProgressStore)
      extends ProgressStore:
    export underlying.{load => _, *}
    def load(): Option[ProgressLog] = None

  /** `underlying` with the stage record failing, as the progress commit does on
    * a full disk.
    */
  private class UnrecordableStages(underlying: ProgressStore)
      extends ProgressStore:
    export underlying.{appendEntry => _, *}
    def appendEntry(entry: StageEntry)(using WorkspaceWrite): Unit =
      throw new IllegalStateException("disk full")

  /** `underlying` with the PR record failing, as it does on a full disk. */
  private class UnrecordablePr(underlying: ProgressStore) extends ProgressStore:
    export underlying.{recordPublished => _, *}
    def recordPublished(work: PublishedWork)(using WorkspaceWrite): Unit =
      throw new IllegalStateException("disk full")
