package orca.tools

import orca.OrcaFlowException
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{CliResult, StubCliRunner}
import ox.either.orThrow

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class OsGitHubToolTest extends munit.FunSuite:

  private def stubGh(response: CliResult): (StubCliRunner, OsGitHubTool) =
    val cli = new StubCliRunner(response)
    (cli, new OsGitHubTool(cli, pollInterval = 10.millis))

  private class CapturingListener extends OrcaListener:
    private val seen = new ConcurrentLinkedQueue[OrcaEvent]()
    def onEvent(event: OrcaEvent): Unit = { val _ = seen.add(event) }
    def events: List[OrcaEvent] = seen.asScala.toList

  private val samplePr = PrHandle("acme", "widgets", 42)

  test("createPr parses the PR URL returned by gh"):
    val (cli, gh) = stubGh(
      CliResult(0, "https://github.com/acme/widgets/pull/42\n", "")
    )
    val pr = gh.createPr("feat: hi", "hello").orThrow
    assertEquals(pr, samplePr)
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "pr", "create")))
    assert(args.containsSlice(Seq("--title", "feat: hi")))
    assert(args.containsSlice(Seq("--body", "hello")))

  test("createPr emits a Step event with the opened PR URL"):
    val listener = new CapturingListener
    val cli = new StubCliRunner(
      CliResult(0, "https://github.com/acme/widgets/pull/42\n", "")
    )
    val gh = new OsGitHubTool(cli, events = listener)
    val _ = gh.createPr("feat: hi", "hello").orThrow
    assertEquals(
      listener.events,
      List(OrcaEvent.Step("Opened PR: https://github.com/acme/widgets/pull/42"))
    )

  test("createPr throws when gh output does not contain a PR URL"):
    val (_, gh) = stubGh(CliResult(0, "no url here", ""))
    val _ = intercept[OrcaFlowException](gh.createPr("t", "b"))

  test("createPr returns Left(PrAlreadyExists) when gh reports a duplicate"):
    val (_, gh) = stubGh(
      CliResult(1, "", "a pull request for branch 'feat' already exists")
    )
    assert(gh.createPr("t", "b").left.exists(_.isInstanceOf[PrAlreadyExists]))

  test(
    "createPr returns Left(NoCommitsToPr) when the branch has nothing to push"
  ):
    val (_, gh) = stubGh(
      CliResult(1, "", "must first push the current branch")
    )
    assert(gh.createPr("t", "b").left.exists(_.isInstanceOf[NoCommitsToPr]))

  test("readPrComments maps gh api JSON into Comment values"):
    val json =
      """[{"body":"looks good","user":{"login":"alice"}},
        | {"body":"ship it","user":{"login":"bob"}}]""".stripMargin
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(
      gh.readPrComments(samplePr),
      List(Comment("alice", "looks good"), Comment("bob", "ship it"))
    )

  test("readIssue parses title, body, author, and state"):
    val json =
      """{"title":"Crash on save","body":"Steps:\n1. open\n2. save",
        | "user":{"login":"reporter"},"state":"open"}""".stripMargin
    val (cli, gh) = stubGh(CliResult(0, json, ""))
    val issue = gh.readIssue(IssueHandle("acme", "widgets", 7))
    assertEquals(
      issue,
      Issue(
        title = "Crash on save",
        body = "Steps:\n1. open\n2. save",
        author = "reporter",
        state = "open"
      )
    )
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("api", "repos/acme/widgets/issues/7")))

  test("readIssue treats a missing body as empty string"):
    val json =
      """{"title":"No body","body":null,"user":{"login":"a"},"state":"open"}"""
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(gh.readIssue(IssueHandle("a", "b", 1)).body, "")

  test("readIssueComments hits the issues/{n}/comments endpoint"):
    val json = """[{"body":"+1","user":{"login":"u"}}]"""
    val (cli, gh) = stubGh(CliResult(0, json, ""))
    val comments = gh.readIssueComments(IssueHandle("acme", "widgets", 7))
    assertEquals(comments, List(Comment("u", "+1")))
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(
      args.containsSlice(
        Seq("api", "--paginate", "repos/acme/widgets/issues/7/comments")
      )
    )

  test("writeComment invokes gh pr comment with the body"):
    val (cli, gh) = stubGh(CliResult(0, "", ""))
    gh.writeComment(samplePr, "nit: whitespace")
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "pr", "comment", "42")))
    assert(args.containsSlice(Seq("--body", "nit: whitespace")))

  test(
    "writeComment(IssueHandle, body) invokes gh issue comment with the body"
  ):
    val (cli, gh) = stubGh(CliResult(0, "", ""))
    gh.writeComment(IssueHandle("acme", "widgets", 7), "follow-up question")
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "issue", "comment", "7")))
    assert(args.containsSlice(Seq("--repo", "acme/widgets")))
    assert(args.containsSlice(Seq("--body", "follow-up question")))

  test("buildStatus reports Success when all checks completed successfully"):
    val json =
      """{"statusCheckRollup":[
        | {"status":"COMPLETED","conclusion":"SUCCESS","name":"test"},
        | {"status":"COMPLETED","conclusion":"SUCCESS","name":"lint"}]}""".stripMargin
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(gh.buildStatus(samplePr).outcome, BuildOutcome.Success)

  test("buildStatus reports Failure when any check failed"):
    val json =
      """{"statusCheckRollup":[
        | {"status":"COMPLETED","conclusion":"SUCCESS","name":"test"},
        | {"status":"COMPLETED","conclusion":"FAILURE","name":"lint"}]}""".stripMargin
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(gh.buildStatus(samplePr).outcome, BuildOutcome.Failure)

  test("buildStatus reports Pending while any check is still running"):
    val json =
      """{"statusCheckRollup":[
        | {"status":"IN_PROGRESS","name":"test"}]}""".stripMargin
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(gh.buildStatus(samplePr).outcome, BuildOutcome.Pending)

  test("buildStatus reports Pending on an empty check list (CI not registered yet)"):
    // Closes the race where waitForBuild would return Success on the first
    // poll after a push, before GitHub had registered the workflow run.
    val (_, gh) = stubGh(CliResult(0, """{"statusCheckRollup":[]}""", ""))
    assertEquals(gh.buildStatus(samplePr).outcome, BuildOutcome.Pending)

  test("waitForBuild polls until the build finishes"):
    val pendingJson =
      """{"statusCheckRollup":[{"status":"IN_PROGRESS","name":"t"}]}"""
    val successJson =
      """{"statusCheckRollup":[{"status":"COMPLETED","conclusion":"SUCCESS","name":"t"}]}"""
    val cli = new StubCliRunner(CliResult(0, pendingJson, ""))
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)

    // Flip the stub to success after a couple of polls.
    val watcher = new Thread(() =>
      Thread.sleep(30)
      cli.setResponse(CliResult(0, successJson, ""))
    )
    watcher.start()
    val status = gh.waitForBuild(samplePr, timeout = 5.seconds).orThrow
    watcher.join()
    assertEquals(status.outcome, BuildOutcome.Success)

  test(
    "waitForBuild doesn't fire NoChecksConfigured once a check was seen"
  ):
    // Sticky-watermark check: once the rollup has a check, a later
    // transient empty rollup (API blip / retry) must not trigger the
    // "no CI configured" fast-path. With timeout > grace, the loop falls
    // through to BuildTimedOut instead.
    val pendingJson =
      """{"statusCheckRollup":[{"status":"IN_PROGRESS","name":"t"}]}"""
    val cli = new StubCliRunner(CliResult(0, pendingJson, ""))
    val gh = new OsGitHubTool(
      cli,
      pollInterval = 5.millis,
      noChecksGrace = 20.millis
    )
    val watcher = new Thread(() =>
      // Wait past the grace window, then flip to an empty rollup. The
      // sticky `sawAnyCheck` should prevent NoChecksConfigured.
      Thread.sleep(40)
      cli.setResponse(CliResult(0, """{"statusCheckRollup":[]}""", ""))
    )
    watcher.start()
    val result = gh.waitForBuild(samplePr, timeout = 200.millis)
    watcher.join()
    assert(result.left.exists(_.isInstanceOf[BuildTimedOut]))

  test(
    "waitForBuild returns Left(NoChecksConfigured) when checks never register"
  ):
    // No CI configured: every poll comes back with an empty rollup. With
    // noChecksGrace < timeout, returning NoChecksConfigured proves the
    // fast-path fired — it's only reachable via the grace branch.
    val cli = new StubCliRunner(CliResult(0, """{"statusCheckRollup":[]}""", ""))
    val gh = new OsGitHubTool(
      cli,
      pollInterval = 10.millis,
      noChecksGrace = 50.millis
    )
    val result = gh.waitForBuild(samplePr, timeout = 5.seconds)
    assert(result.left.exists(_.isInstanceOf[NoChecksConfigured]))

  test("waitForBuild returns Left(BuildTimedOut) when the deadline elapses"):
    val pendingJson =
      """{"statusCheckRollup":[{"status":"IN_PROGRESS","name":"t"}]}"""
    val (_, gh) = stubGh(CliResult(0, pendingJson, ""))
    assert(
      gh.waitForBuild(samplePr, timeout = 30.millis)
        .left
        .exists(_.isInstanceOf[BuildTimedOut])
    )
