package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}
import orca.OrcaFlowException
import orca.subprocess.CliRunner

import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class PrHandle(owner: String, repo: String, number: Int)

/** Lightweight reference to a GitHub issue. The number is what `gh issue view
  * <n>` shows; the owner/repo route the API call.
  */
case class IssueHandle(owner: String, repo: String, number: Int)

case class Comment(author: String, body: String)

/** Snapshot of an issue's top-level fields — the bits a flow typically wants
  * when triaging or planning from an issue body. Comments live on a separate
  * endpoint and are read via [[GitHubTool.readIssueComments]].
  */
case class Issue(
    title: String,
    body: String,
    author: String,
    state: String
)

enum BuildOutcome:
  case Pending
  case Success
  case Failure

case class BuildStatus(outcome: BuildOutcome, log: String)

/** Recoverable [[GitHubTool.createPr]] failure modes. Common when re-running a
  * flow against an already-pushed branch. Other gh failures (auth, network)
  * remain thrown.
  */
sealed abstract class PrCreateFailed(message: String)
    extends OrcaFlowException(message)

class PrAlreadyExists
    extends PrCreateFailed(
      "a pull request for the current branch already exists"
    )

class NoCommitsToPr
    extends PrCreateFailed(
      "no commits to open a pull request from — push the branch first"
    )

/** Returned in the `Left` of [[GitHubTool.waitForBuild]] when the timeout
  * elapses while the build is still pending. The caller can decide whether to
  * keep waiting, escalate to a human, or abort.
  */
class BuildTimedOut(timeout: FiniteDuration)
    extends OrcaFlowException(s"build did not finish within $timeout")

/** GitHub adapter usable from flow scripts — the handle behind the `gh`
  * accessor. Creates pull requests, reads issues and their comments, reads and
  * writes PR comments, and polls GitHub's check-run status.
  */
trait GitHubTool:
  def createPr(title: String, body: String): Either[PrCreateFailed, PrHandle]

  /** Fetch the issue's title, body, author, and state. */
  def readIssue(issue: IssueHandle): Issue

  /** Fetch the conversation comments on an issue (the comments the GitHub UI
    * shows under the body, in posting order).
    */
  def readIssueComments(issue: IssueHandle): List[Comment]

  /** Fetch the conversation comments on a PR (issue-style comments, not
    * line-level review comments — those live on a separate endpoint).
    */
  def readPrComments(pr: PrHandle): List[Comment]

  /** Post a top-level issue-style comment on a pull request (the comments the
    * GitHub UI shows under the description, not line-level review comments).
    */
  def writeComment(pr: PrHandle, body: String): Unit

  /** Post a top-level comment on an issue. Used by assess-then-act flows to
    * surface a follow-up question / critique / rebuff back to the reporter
    * when no PR will be opened.
    */
  def writeComment(issue: IssueHandle, body: String): Unit
  def buildStatus(pr: PrHandle): BuildStatus
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration
  ): Either[BuildTimedOut, BuildStatus]

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

private[orca] case class GhIssueJson(
    title: String,
    // Issues without a body return `null` from the API; the codec
    // treats a missing key as None and a null literal as None too.
    body: Option[String] = None,
    user: GhUserJson,
    state: String
) derives ConfiguredJsonValueCodec

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

  def createPr(title: String, body: String): Either[PrCreateFailed, PrHandle] =
    // Inspect exit code + stderr ourselves so we can split the recoverable
    // "branch already has a PR" / "no commits to push" cases out from
    // genuine system failures.
    val result = cli.run(
      Seq("gh", "pr", "create", "--title", title, "--body", body),
      cwd = workDir
    )
    if result.exitCode == 0 then
      val output = result.stdout.trim
      PrUrlPattern.findFirstMatchIn(output) match
        case Some(m) =>
          Right(PrHandle(m.group(1), m.group(2), m.group(3).toInt))
        case None =>
          throw OrcaFlowException(
            s"Unexpected output from gh pr create: $output"
          )
    else
      val combined = (result.stdout + "\n" + result.stderr).toLowerCase
      if combined.contains("already exists") then Left(new PrAlreadyExists)
      else if combined.contains("no commits") ||
        combined.contains("must first push")
      then Left(new NoCommitsToPr)
      else
        throw OrcaFlowException(
          s"gh pr create failed (exit ${result.exitCode}): ${result.stderr}"
        )

  def readIssue(issue: IssueHandle): Issue =
    val output = gh(
      "api",
      s"repos/${issue.owner}/${issue.repo}/issues/${issue.number}"
    )
    val parsed = readFromString[GhIssueJson](output)
    Issue(
      title = parsed.title,
      body = parsed.body.getOrElse(""),
      author = parsed.user.login,
      state = parsed.state
    )

  def readIssueComments(issue: IssueHandle): List[Comment] =
    readCommentsAt(issue.owner, issue.repo, issue.number)

  def readPrComments(pr: PrHandle): List[Comment] =
    // GitHub's `/issues/{n}/comments` endpoint returns the conversation
    // comments for both issues and PRs (a PR is an issue in the data
    // model). Line-level review comments live at `/pulls/{n}/comments`
    // and aren't covered here.
    readCommentsAt(pr.owner, pr.repo, pr.number)

  private def readCommentsAt(
      owner: String,
      repo: String,
      number: Int
  ): List[Comment] =
    val output = gh(
      "api",
      "--paginate",
      s"repos/$owner/$repo/issues/$number/comments"
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

  def writeComment(issue: IssueHandle, body: String): Unit =
    val _ = gh(
      "issue",
      "comment",
      issue.number.toString,
      "--repo",
      s"${issue.owner}/${issue.repo}",
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

  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration
  ): Either[BuildTimedOut, BuildStatus] =
    val deadline = System.nanoTime() + timeout.toNanos

    @scala.annotation.tailrec
    def loop(): Either[BuildTimedOut, BuildStatus] =
      val status = buildStatus(pr)
      if status.outcome != BuildOutcome.Pending then Right(status)
      else if System.nanoTime() >= deadline then
        Left(new BuildTimedOut(timeout))
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
