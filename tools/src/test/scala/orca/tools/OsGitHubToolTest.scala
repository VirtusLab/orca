package orca.tools

import orca.OrcaFlowException
import orca.events.{OrcaEvent, OrcaListener}
import orca.subprocess.{
  CliCall,
  CliResult,
  CliRunner,
  PipedCliProcess,
  StubCliRunner
}
import ox.either.orThrow
import ox.scheduling.Schedule

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
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

  /** A `CliRunner` that returns each response in turn (the last repeats), for
    * tests that need consecutive `run` calls to differ — e.g. fail then
    * succeed. Records every call so tests can assert on args. Only `run` is
    * exercised here.
    */
  private class SequencedCliRunner(responses: List[CliResult])
      extends CliRunner:
    private val next = new AtomicInteger(0)
    private val callsCount = new AtomicInteger(0)
    private val recordedCalls: AtomicReference[List[CliCall]] =
      AtomicReference(Nil)
    def callCount: Int = callsCount.get()
    def calls: List[CliCall] = recordedCalls.get().reverse
    def run(
        args: Seq[String],
        stdin: String,
        env: Map[String, String],
        cwd: os.Path
    ): CliResult =
      val _ = callsCount.incrementAndGet()
      val _ =
        recordedCalls.updateAndGet(cs =>
          CliCall(args.toList, stdin, env, cwd) :: cs
        )
      responses(math.min(next.getAndIncrement(), responses.size - 1))
    def spawnPiped(
        args: Seq[String],
        env: Map[String, String],
        cwd: os.Path,
        pipeStderr: Boolean
    ): PipedCliProcess =
      throw new UnsupportedOperationException("not supported in this stub")

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

  test(
    "createPr returns Left(PrAlreadyExists) when gh reports a duplicate and no open PR is found"
  ):
    // gh pr create reports duplicate; git rev-parse gives branch name; gh pr list
    // returns empty → fallback Left(PrAlreadyExists).
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", "a pull request for branch 'feat' already exists"),
        CliResult(0, "feat\n", ""), // git rev-parse
        CliResult(0, "[]", "") // gh pr list — no open PR
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
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

  test("updatePr patches the PR via the REST API with the new title and body"):
    val (cli, gh) = stubGh(CliResult(0, "", ""))
    gh.updatePr(samplePr, "Fix overflow", "full description")
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "api", "-X", "PATCH")))
    assert(args.contains("repos/acme/widgets/pulls/42"))
    assert(args.containsSlice(Seq("-f", "title=Fix overflow")))
    assert(args.containsSlice(Seq("-f", "body=full description")))

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

  test(
    "buildStatus reports Pending on an empty check list (CI not registered yet)"
  ):
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

  test("readIssue retries a transient gh failure then succeeds"):
    val issueJson =
      """{"title":"t","body":"b","user":{"login":"u"},"state":"open"}"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", """Post "https://api.github.com/graphql": EOF"""),
        CliResult(0, issueJson, "")
      )
    )
    val gh = new OsGitHubTool(cli, readRetry = Schedule.immediate.maxRetries(2))
    assertEquals(gh.readIssue(IssueHandle("a", "b", 1)).title, "t")
    assertEquals(cli.callCount, 2) // one failed attempt, then success

  test("waitForBuild rides out a transient gh failure within one poll"):
    // A dropped GraphQL connection makes `gh` exit non-zero (OrcaFlowException);
    // the per-poll Ox retry should absorb a couple of these and still return the
    // eventual status rather than aborting the whole wait.
    val successJson =
      """{"statusCheckRollup":[{"status":"COMPLETED","conclusion":"SUCCESS","name":"t"}]}"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", """Post "https://api.github.com/graphql": EOF"""),
        CliResult(1, "", """Post "https://api.github.com/graphql": EOF"""),
        CliResult(0, successJson, "")
      )
    )
    val gh = new OsGitHubTool(
      cli,
      pollInterval = 1.milli,
      readRetry = Schedule.immediate.maxRetries(3)
    )
    val status = gh.waitForBuild(samplePr, timeout = 5.seconds).orThrow
    assertEquals(status.outcome, BuildOutcome.Success)
    assertEquals(cli.callCount, 3) // two failed attempts, then success

  test("waitForBuild surfaces a gh failure that outlasts the poll retry"):
    // A persistent failure (bad auth, repo gone) must still abort once the
    // bounded retry is exhausted — it doesn't silently spin until the timeout.
    val cli = new SequencedCliRunner(
      List(CliResult(1, "", "gh: not authenticated"))
    )
    val gh = new OsGitHubTool(
      cli,
      pollInterval = 1.milli,
      readRetry = Schedule.immediate.maxRetries(2)
    )
    val ex = intercept[OrcaFlowException](
      gh.waitForBuild(samplePr, timeout = 5.seconds)
    )
    assert(ex.getMessage.contains("not authenticated"), ex.getMessage)
    assertEquals(cli.callCount, 3) // initial attempt + two retries

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
    val gh = new OsGitHubTool(cli, pollInterval = 5.millis)
    val watcher = new Thread(() =>
      // Wait past the grace window, then flip to an empty rollup. The
      // sticky `sawAnyCheck` should prevent NoChecksConfigured.
      Thread.sleep(40)
      cli.setResponse(CliResult(0, """{"statusCheckRollup":[]}""", ""))
    )
    watcher.start()
    val result = gh.waitForBuild(
      samplePr,
      timeout = 200.millis,
      noChecksGrace = 20.millis
    )
    watcher.join()
    assert(result.left.exists(_.isInstanceOf[BuildTimedOut]))

  test(
    "waitForBuild returns Left(NoChecksConfigured) when checks never register"
  ):
    // No CI configured: every poll comes back with an empty rollup. With
    // noChecksGrace < timeout, returning NoChecksConfigured proves the
    // fast-path fired — it's only reachable via the grace branch.
    val cli =
      new StubCliRunner(CliResult(0, """{"statusCheckRollup":[]}""", ""))
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    val result = gh.waitForBuild(
      samplePr,
      timeout = 5.seconds,
      noChecksGrace = 50.millis
    )
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

  // ── createPr idempotency (R24) ────────────────────────────────────────────

  test(
    "createPr returns Right(existing PR) when gh reports 'already exists' and findOpenPr succeeds"
  ):
    // Call 1: gh pr create exits 1 with "already exists"
    // Call 2: git rev-parse to get the current branch name
    // Call 3: gh pr list returns JSON with the existing PR
    val prListJson =
      """[{"number":42,"url":"https://github.com/acme/widgets/pull/42","headRefName":"feat"}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", "a pull request for branch 'feat' already exists"),
        CliResult(0, "feat\n", ""), // git rev-parse
        CliResult(0, prListJson, "") // gh pr list
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    val pr = gh.createPr("feat: hi", "hello").orThrow
    assertEquals(pr, samplePr)

  test(
    "createPr returns Left(PrAlreadyExists) when gh reports 'already exists' but findOpenPr finds nothing"
  ):
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", "a pull request for branch 'feat' already exists"),
        CliResult(0, "feat\n", ""), // git rev-parse
        CliResult(0, "[]", "") // empty list — no open PR found
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    assert(gh.createPr("t", "b").left.exists(_.isInstanceOf[PrAlreadyExists]))

  test(
    "createPr emits 'Reusing existing PR' step event when PR already exists"
  ):
    val listener = new CapturingListener
    val prListJson =
      """[{"number":42,"url":"https://github.com/acme/widgets/pull/42","headRefName":"feat"}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(1, "", "a pull request for branch 'feat' already exists"),
        CliResult(0, "feat\n", ""), // git rev-parse
        CliResult(0, prListJson, "") // gh pr list
      )
    )
    val gh = new OsGitHubTool(cli, events = listener, pollInterval = 10.millis)
    val _ = gh.createPr("feat: hi", "hello").orThrow
    assert(
      listener.events.exists:
        case OrcaEvent.Step(msg) => msg.contains("Reusing existing PR")
        case _                   => false
    )

  test(
    "createPr still returns Left(NoCommitsToPr) when branch has nothing to push"
  ):
    val (_, gh) = stubGh(
      CliResult(1, "", "must first push the current branch")
    )
    assert(gh.createPr("t", "b").left.exists(_.isInstanceOf[NoCommitsToPr]))

  // ── upsertComment (R24) ──────────────────────────────────────────────────

  test(
    "upsertComment on PrHandle creates a new comment (with marker) when no comment matches"
  ):
    val commentsJson =
      """[{"id":1,"body":"unrelated","user":{"login":"alice"}}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(0, commentsJson, ""), // fetchIdentifiedComments
        CliResult(0, "", "") // writeComment create
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    gh.upsertComment(samplePr, "<!-- orca:abc:reject -->", "Rejected.")
    // The create call must embed the marker in the body
    val createCall = cli.calls.last
    assert(createCall.args.containsSlice(Seq("gh", "pr", "comment")))
    val markedBody = "Rejected.\n\n<!-- orca:abc:reject -->"
    assert(createCall.args.contains(markedBody))

  test(
    "upsertComment on PrHandle updates existing comment via PATCH when marker found"
  ):
    val commentsJson =
      """[{"id":99,"body":"Old text\n\n<!-- orca:abc:reject -->","user":{"login":"alice"}}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(0, commentsJson, ""), // fetchIdentifiedComments
        CliResult(0, "", "") // PATCH update
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    gh.upsertComment(samplePr, "<!-- orca:abc:reject -->", "New text.")
    val patchCall = cli.calls.last
    assert(patchCall.args.containsSlice(Seq("gh", "api", "-X", "PATCH")))
    // The path must include the comment id 99
    assert(patchCall.args.exists(_.contains("/comments/99")))
    // The body must include both the new text and the marker
    val markedBody = "body=New text.\n\n<!-- orca:abc:reject -->"
    assert(patchCall.args.contains(markedBody))

  test(
    "upsertComment on IssueHandle creates a new comment (with marker) when no comment matches"
  ):
    val commentsJson =
      """[{"id":1,"body":"unrelated","user":{"login":"alice"}}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(0, commentsJson, ""), // fetchIdentifiedComments
        CliResult(0, "", "") // writeComment create
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    val issue = IssueHandle("acme", "widgets", 7)
    gh.upsertComment(issue, "<!-- orca:abc:triage -->", "Not a bug.")
    val createCall = cli.calls.last
    assert(createCall.args.containsSlice(Seq("gh", "issue", "comment")))
    val markedBody = "Not a bug.\n\n<!-- orca:abc:triage -->"
    assert(createCall.args.contains(markedBody))

  test(
    "upsertComment on IssueHandle updates existing comment via PATCH when marker found"
  ):
    val commentsJson =
      """[{"id":77,"body":"Old.\n\n<!-- orca:abc:triage -->","user":{"login":"alice"}}]"""
    val cli = new SequencedCliRunner(
      List(
        CliResult(0, commentsJson, ""), // fetchIdentifiedComments
        CliResult(0, "", "") // PATCH update
      )
    )
    val gh = new OsGitHubTool(cli, pollInterval = 10.millis)
    val issue = IssueHandle("acme", "widgets", 7)
    gh.upsertComment(issue, "<!-- orca:abc:triage -->", "Updated.")
    val patchCall = cli.calls.last
    assert(patchCall.args.containsSlice(Seq("gh", "api", "-X", "PATCH")))
    assert(patchCall.args.exists(_.contains("/comments/77")))
