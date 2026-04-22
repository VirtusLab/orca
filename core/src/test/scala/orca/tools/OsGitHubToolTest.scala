package orca.tools

import orca.{BuildOutcome, Comment, OrcaFlowException, PrHandle}
import orca.subprocess.{CliResult, StubCliRunner}

import scala.concurrent.duration.DurationInt

class OsGitHubToolTest extends munit.FunSuite:

  private def stubGh(response: CliResult): (StubCliRunner, OsGitHubTool) =
    val cli = new StubCliRunner(response)
    (cli, new OsGitHubTool(cli, pollInterval = 10.millis))

  private val samplePr = PrHandle("acme", "widgets", 42)

  test("createPr parses the PR URL returned by gh"):
    val (cli, gh) = stubGh(
      CliResult(0, "https://github.com/acme/widgets/pull/42\n", "")
    )
    val pr = gh.createPr("feat: hi", "hello")
    assertEquals(pr, samplePr)
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "pr", "create")))
    assert(args.containsSlice(Seq("--title", "feat: hi")))
    assert(args.containsSlice(Seq("--body", "hello")))

  test("createPr throws when gh output does not contain a PR URL"):
    val (_, gh) = stubGh(CliResult(0, "no url here", ""))
    val _ = intercept[OrcaFlowException](gh.createPr("t", "b"))

  test("readComments maps gh api JSON into Comment values"):
    val json =
      """[{"body":"looks good","user":{"login":"alice"}},
        | {"body":"ship it","user":{"login":"bob"}}]""".stripMargin
    val (_, gh) = stubGh(CliResult(0, json, ""))
    assertEquals(
      gh.readComments(samplePr),
      List(Comment("alice", "looks good"), Comment("bob", "ship it"))
    )

  test("writeComment invokes gh pr comment with the body"):
    val (cli, gh) = stubGh(CliResult(0, "", ""))
    gh.writeComment(samplePr, "nit: whitespace")
    val args = cli.lastCall.getOrElse(fail("expected a call")).args
    assert(args.containsSlice(Seq("gh", "pr", "comment", "42")))
    assert(args.containsSlice(Seq("--body", "nit: whitespace")))

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
    val status = gh.waitForBuild(samplePr, timeout = 5.seconds)
    watcher.join()
    assertEquals(status.outcome, BuildOutcome.Success)

  test("waitForBuild throws when the timeout elapses while still pending"):
    val pendingJson =
      """{"statusCheckRollup":[{"status":"IN_PROGRESS","name":"t"}]}"""
    val (_, gh) = stubGh(CliResult(0, pendingJson, ""))
    val _ = intercept[OrcaFlowException](
      gh.waitForBuild(samplePr, timeout = 30.millis)
    )
