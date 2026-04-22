package orca.tools

import orca.{
  BuildOutcome,
  BuildStatus,
  Comment,
  GitHubTool,
  OrcaFlowException,
  PrHandle
}
import orca.subprocess.CliRunner

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

private[orca] case class GhCheck(
    // CheckRun entries use `status`/`conclusion`; legacy commit-status entries
    // use only `state`. Both are optional so a single codec handles both.
    status: Option[String] = None,
    conclusion: Option[String] = None,
    state: Option[String] = None,
    name: Option[String] = None
) derives ConfiguredJsonValueCodec

private[orca] case class GhCheckRollup(
    statusCheckRollup: List[GhCheck]
) derives ConfiguredJsonValueCodec

private[orca] case class GhCommentJson(
    body: String,
    user: GhUserJson
) derives ConfiguredJsonValueCodec

private[orca] case class GhUserJson(login: String)
    derives ConfiguredJsonValueCodec

private[orca] given JsonValueCodec[List[GhCommentJson]] = JsonCodecMaker.make

/** GitHubTool implementation that shells out to the `gh` CLI via a `CliRunner`.
  * `waitForBuild` polls `buildStatus` every `pollInterval` until a terminal
  * outcome or the caller-supplied timeout expires.
  */
class OsGitHubTool(
    cli: CliRunner,
    workDir: os.Path = os.pwd,
    pollInterval: FiniteDuration = 30.seconds
) extends GitHubTool:

  import OsGitHubTool.*

  private val PrUrlPattern =
    """https://github\.com/([^/]+)/([^/]+)/pull/(\d+)""".r

  def createPr(title: String, body: String): PrHandle =
    val output = gh("pr", "create", "--title", title, "--body", body).trim
    PrUrlPattern.findFirstMatchIn(output) match
      case Some(m) => PrHandle(m.group(1), m.group(2), m.group(3).toInt)
      case None =>
        throw OrcaFlowException(s"Unexpected output from gh pr create: $output")

  def readComments(pr: PrHandle): List[Comment] =
    val output = gh(
      "api",
      "--paginate",
      s"repos/${pr.owner}/${pr.repo}/issues/${pr.number}/comments"
    )
    readFromString[List[GhCommentJson]](output).map: c =>
      Comment(author = c.user.login, body = c.body)

  def writeComment(pr: PrHandle, body: String): Unit =
    val _ = gh(
      "pr",
      "comment",
      pr.number.toString,
      "--repo",
      s"${pr.owner}/${pr.repo}",
      "--body",
      body
    )

  def buildStatus(pr: PrHandle): BuildStatus =
    val output = gh(
      "pr",
      "view",
      pr.number.toString,
      "--repo",
      s"${pr.owner}/${pr.repo}",
      "--json",
      "statusCheckRollup"
    )
    val rollup = readFromString[GhCheckRollup](output)
    val outcome = aggregateOutcome(rollup.statusCheckRollup)
    val log = rollup.statusCheckRollup
      .map: c =>
        val tag = c.conclusion
          .orElse(c.state)
          .orElse(c.status)
          .getOrElse("?")
        s"${c.name.getOrElse("?")}: $tag"
      .mkString("\n")
    BuildStatus(outcome, log)

  def waitForBuild(pr: PrHandle, timeout: FiniteDuration): BuildStatus =
    val deadline = System.nanoTime() + timeout.toNanos

    @scala.annotation.tailrec
    def loop(): BuildStatus =
      val status = buildStatus(pr)
      if status.outcome != BuildOutcome.Pending then status
      else if System.nanoTime() >= deadline then
        throw OrcaFlowException(
          s"Build for PR ${pr.number} did not finish within $timeout"
        )
      else
        Thread.sleep(pollInterval.toMillis)
        loop()

    loop()

  private def gh(args: String*): String =
    val result = cli.run("gh" +: args, cwd = workDir)
    if result.exitCode != 0 then
      throw OrcaFlowException(
        s"gh ${args.mkString(" ")} failed (exit ${result.exitCode}): ${result.stderr}"
      )
    result.stdout

private[orca] object OsGitHubTool:

  private val StatusCompleted = "COMPLETED"
  private val SuccessfulConclusions = Set("SUCCESS", "NEUTRAL", "SKIPPED")
  private val LegacyStateSuccess = "SUCCESS"
  private val LegacyStatePending = "PENDING"

  /** Reduce a heterogeneous list of check entries to a single outcome. Empty
    * list is treated as Success — GitHub returns no checks when none are
    * configured, and callers would otherwise block forever on an empty list of
    * "pending" checks.
    */
  def aggregateOutcome(checks: List[GhCheck]): BuildOutcome =
    if checks.isEmpty then BuildOutcome.Success
    else if checks.exists(isPending) then BuildOutcome.Pending
    else if checks.forall(isSuccess) then BuildOutcome.Success
    else BuildOutcome.Failure

  private def isPending(c: GhCheck): Boolean =
    c.status.exists(_ != StatusCompleted) ||
      c.state.contains(LegacyStatePending) ||
      (c.status.isEmpty && c.state.isEmpty && c.conclusion.isEmpty)

  private def isSuccess(c: GhCheck): Boolean =
    c.conclusion.exists(SuccessfulConclusions.contains) ||
      c.state.contains(LegacyStateSuccess)
