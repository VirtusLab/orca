package orca

import scala.concurrent.duration.FiniteDuration

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
  def writeComment(pr: PrHandle, body: String): Unit
  def buildStatus(pr: PrHandle): BuildStatus
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration
  ): Either[BuildTimedOut, BuildStatus]
