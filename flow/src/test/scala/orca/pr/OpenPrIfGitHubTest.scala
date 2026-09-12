package orca.pr

import munit.FunSuite
import orca.tools.{
  BranchNotPushed,
  GitHubAvailability,
  PrCreateFailed,
  PrHandle,
  PushFailure
}
import orca.{FlowControl, OrcaFlowException, WorkspaceWrite}
import orca.events.{OrcaEvent, OrcaListener}
import orca.progress.{BranchMode, ProgressHeader, ProgressStore}
import orca.testkit.GitRepo

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrIfGitHub]] — the best-effort wrapper around
  * [[openPrFromBranch]]. When the probe says no PR can be opened, the run is
  * left untouched: no stage is entered (a recorded stage would replay as "done"
  * on resume), neither `git.push` nor `gh.createPr` runs, and the lifecycle is
  * told no PR exists. When a leg refuses mid-way the stages have run, and what
  * matters is that the refusal comes back as a line and `None` rather than an
  * exception. Either way the user gets one line saying why.
  */
class OpenPrIfGitHubTest extends FunSuite:

  /** What one `openPrIfGitHub` run produced. */
  private case class Run(
      result: Option[PrHandle],
      calls: List[String],
      stages: List[String],
      steps: List[String],
      errors: List[String],
      openedPr: Option[PrHandle]
  )

  private def run(
      availability: GitHubAvailability,
      withCode: Boolean = true,
      branchMode: BranchMode = BranchMode.Created,
      createPr: => Either[PrCreateFailed, PrHandle] = Right(samplePr),
      push: => Either[PushFailure, Unit] = Right(()),
      base: => String = "main"
  ): Run =
    val (dir, store) = seededPrRepo(withCode, branchMode)
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val steps = new ConcurrentLinkedQueue[String]()
    val errors = new ConcurrentLinkedQueue[String]()
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
      base = base
    )
    val result = openPrIfGitHub(summarisingAgent = new StubSummariser())(using
      control,
      control
    )
    Run(
      result,
      calls.asScala.toList,
      stages.asScala.toList,
      steps.asScala.toList,
      errors.asScala.toList,
      control.openedPr
    )

  /** Every skip leg leaves the run as it found it, so assert that once and let
    * each test add what its own answer should say.
    */
  private def assertSkipped(r: Run): Unit =
    assertEquals(r.result, None)
    assertEquals(r.stages, Nil)
    assertEquals(r.calls, List("availability"))
    assertEquals(r.openedPr, None)
    assertEquals(r.steps.size, 1)
    assert(r.steps.head.contains("no PR opened"), r.steps.head)
    assertEquals(r.errors, Nil)

  test("without a remote, no PR is opened and the line says to add one"):
    val r = run(GitHubAvailability.NoRemote)
    assertSkipped(r)
    assert(r.steps.head.contains("no git remote"), r.steps.head)

  test("off GitHub, no PR is opened and the line offers the host login"):
    val r = run(GitHubAvailability.NotGitHub("ghe.example.com"))
    assertSkipped(r)
    assert(
      r.steps.head.contains("gh auth login --hostname ghe.example.com"),
      r.steps.head
    )

  test("with a hostless origin, no PR is opened and no login is suggested"):
    // A local-path clone has no host to log in to, so the line must not offer
    // a `--hostname` that cannot work.
    val r = run(GitHubAvailability.NoHost("/srv/repos/widgets.git"))
    assertSkipped(r)
    assert(r.steps.head.contains("/srv/repos/widgets.git"), r.steps.head)
    assert(!r.steps.head.contains("--hostname"), r.steps.head)

  test("with GitHub unreachable, the line ends in gh's own next action"):
    val r = run(
      GitHubAvailability
        .Unreachable("github.com", "run `gh auth login --hostname github.com`")
    )
    assertSkipped(r)
    assert(
      r.steps.head.contains("run `gh auth login --hostname github.com`"),
      r.steps.head
    )

  test("on GitHub, the probe runs first and the PR is opened"):
    val r = run(GitHubAvailability.Available("github.com", "acme", "widgets"))
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.openedPr, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))
    // gh resolves the base repo from the checkout's remotes, so where the PR
    // lands is named before the push rather than only in the resulting URL.
    assert(
      r.steps.head.contains("Opening a PR on github.com/acme/widgets"),
      r.steps
    )

  test("a run that changed no code opens no PR"):
    // The branch carries only orca's progress log. A PR for it would be empty,
    // and teardown deletes a branch like that.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      withCode = false
    )
    assertEquals(r.result, None)
    assertEquals(r.openedPr, None)
    assert(r.steps.last.contains("changed no code"), r.steps.last)
    // Nothing is announced for a PR that is not going to be opened.
    assert(!r.steps.exists(_.contains("Opening a PR on")), r.steps)

  test("a run on a reused branch opens its PR without the no-code check"):
    // Under --skip-branch the header's starting branch IS the run's branch, so
    // a diff between the two would say "no code" whatever the run committed.
    // The lifecycle never treats a reused branch as throwaway either.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      withCode = false,
      branchMode = BranchMode.Reused
    )
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))

  test(
    "the no-code check measures the run's start point, not the default base"
  ):
    // Started from `develop`, which is ahead of the default base `main`
    // (`PushlessGit.defaultBase`). The run itself changed nothing, so no PR —
    // measuring against `main` would see develop's commits and open one.
    val dir = GitRepo.seeded()
    val _ = os.proc("git", "checkout", "-b", "develop").call(cwd = dir)
    os.write(dir / "ahead.txt", "earlier work")
    val _ = os.proc("git", "add", "ahead.txt").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "earlier").call(cwd = dir)
    val _ = os.proc("git", "checkout", "-b", "feat/test").call(cwd = dir)
    val store = ProgressStore.default(dir, "p")
    given WorkspaceWrite = WorkspaceWrite.unsafe
    store.writeHeader(
      ProgressHeader("develop", "feat/test", "deadbeef", BranchMode.Created)
    )
    val calls = new ConcurrentLinkedQueue[String]()
    val steps = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.Step(message) => steps.add(message): Unit
      case _                       => ()
    val control = prControl(
      dir,
      store,
      listener,
      calls,
      availability = GitHubAvailability.Available("github.com", "acme", "w")
    )
    val result = openPrIfGitHub(summarisingAgent = new StubSummariser())(using
      control,
      control
    )
    assertEquals(result, None)
    assertEquals(calls.asScala.toList, List("availability"))
    assert(steps.asScala.exists(_.contains("changed no code")), steps)

  test("no resolvable base branch is reported before anything is pushed"):
    // `git.defaultBase()` throws when neither origin/HEAD nor origin/main nor
    // origin/master resolves — an environment answer, read before the push.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      base = throw OrcaFlowException("no default base ref found")
    )
    assertEquals(r.result, None)
    assertEquals(r.calls, List("availability"))
    assert(r.steps.last.contains("base branch"), r.steps.last)
    assert(r.steps.last.contains("no PR opened"), r.steps.last)

  test("a refused push is reported, and the create is never reached"):
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      push = Left(new PushFailure.RemoteDeclined("protected branch"))
    )
    assertEquals(r.result, None)
    assertEquals(r.openedPr, None)
    assertEquals(r.calls, List("availability", "push"))
    assert(r.steps.last.contains("could not push the branch"), r.steps.last)

  test("a push that fails outside git's modelled refusals is reported too"):
    // No push permission, an expired credential, no network: git throws those
    // rather than returning them, and they are the likeliest reason a PR
    // cannot be opened.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      push = throw OrcaFlowException(
        "git push failed (exit 128): remote: Permission to acme/widgets denied"
      )
    )
    assertEquals(r.result, None)
    assertEquals(r.openedPr, None)
    assert(r.steps.last.contains("could not push the branch"), r.steps.last)
    assert(r.steps.last.contains("Permission to acme/widgets"), r.steps.last)

  test("a create that throws outside orca's own exceptions is reported too"):
    // gh output the tool cannot parse (the already-exists lookup decodes JSON)
    // throws a plain runtime exception; best effort absorbs it like the rest.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      createPr = throw new RuntimeException("unexpected end of input\nat 0x0")
    )
    assertEquals(r.result, None)
    assertEquals(r.openedPr, None)
    assert(r.steps.last.contains("could not open a PR"), r.steps.last)
    assert(r.steps.last.contains("unexpected end of input"), r.steps.last)
    // Only the first line: the reason lands in one Step.
    assert(!r.steps.last.contains("\n"), r.steps.last)

  test("a resumed run replays its opened PR even when the probe now says no"):
    // The first attempt pushed and opened the PR; on resume the push stage is
    // recorded, so the probe is not consulted — its answer could only hide a
    // PR that already exists — and the replayed handle reaches the lifecycle.
    val (dir, store) = seededPrRepo()
    val summariser = new StubSummariser()
    def attempt(
        availability: GitHubAvailability,
        calls: ConcurrentLinkedQueue[String]
    ): (Option[PrHandle], FlowControl) =
      val control =
        prControl(dir, store, _ => (), calls, availability = availability)
      val result = openPrIfGitHub(summarisingAgent = summariser)(using
        control,
        control
      )
      (result, control)

    val _ = attempt(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      new ConcurrentLinkedQueue[String]()
    )
    val resumedCalls = new ConcurrentLinkedQueue[String]()
    val (result, resumed) = attempt(
      GitHubAvailability.Unreachable("github.com", "gh: connection refused"),
      resumedCalls
    )
    assertEquals(resumedCalls.asScala.toList, Nil, "stages were re-run")
    assertEquals(result, Some(samplePr))
    assertEquals(resumed.openedPr, Some(samplePr))

  test("a refused PR creation is reported, not thrown"):
    // `createPr` models its refusals as values and `openPrFromBranch` throws
    // them; best effort turns them back into one line and a finished run.
    val r = run(
      GitHubAvailability.Available("github.com", "acme", "widgets"),
      createPr = Left(new BranchNotPushed)
    )
    assertEquals(r.result, None)
    assertEquals(r.openedPr, None)
    assert(r.steps.last.contains("could not open a PR"), r.steps.last)
    assert(r.steps.last.contains("no PR opened"), r.steps.last)
    // The stage reported before it threw, so the user sees that error and then
    // the reason — and the run still succeeds.
    assert(r.errors.exists(_.contains("Open PR")), r.errors)
