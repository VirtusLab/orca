package orca.pr

import munit.FunSuite
import orca.tools.{GitHubAvailability, PrHandle}
import orca.events.{OrcaEvent, OrcaListener}

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for [[openPrIfGitHub]] — the best-effort wrapper around
  * [[openPrFromBranch]]. What matters when no PR can be opened is that the run
  * is left untouched: no stage is entered (a recorded stage would replay as
  * "done" on resume), neither `git.push` nor `gh.createPr` runs, and the
  * lifecycle is told no PR exists. The user gets exactly one line saying why.
  */
class OpenPrIfGitHubTest extends FunSuite:

  /** What one `openPrIfGitHub` run produced. */
  private case class Run(
      result: Option[PrHandle],
      calls: List[String],
      stages: List[String],
      steps: List[String],
      openedPr: Option[PrHandle]
  )

  private def run(availability: GitHubAvailability): Run =
    val (dir, store) = seededPrRepo()
    val calls = new ConcurrentLinkedQueue[String]()
    val stages = new ConcurrentLinkedQueue[String]()
    val steps = new ConcurrentLinkedQueue[String]()
    val listener: OrcaListener =
      case OrcaEvent.StageStarted(name) => stages.add(name): Unit
      case OrcaEvent.Step(message)      => steps.add(message): Unit
      case _                            => ()

    val control =
      prControl(dir, store, listener, calls, availability = availability)
    val result = openPrIfGitHub(summarisingAgent = new StubSummariser())(using
      control,
      control
    )
    Run(
      result,
      calls.asScala.toList,
      stages.asScala.toList,
      steps.asScala.toList,
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
      r.steps.head.endsWith("run `gh auth login --hostname github.com`"),
      r.steps.head
    )

  test("on GitHub, the probe runs first and the PR is opened"):
    val r = run(GitHubAvailability.Available("github.com", "acme", "widgets"))
    assertEquals(r.result, Some(samplePr))
    assertEquals(r.calls, List("availability", "push", "createPr"))
