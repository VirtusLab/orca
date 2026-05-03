package orca

import scala.concurrent.duration.FiniteDuration

case class PrHandle(owner: String, repo: String, number: Int)

case class Comment(author: String, body: String)

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
  * accessor. Creates pull requests, reads and writes comments, and polls
  * GitHub's check-run status.
  */
trait GitHubTool:
  def createPr(title: String, body: String): Either[PrCreateFailed, PrHandle]
  def readComments(pr: PrHandle): List[Comment]
  def writeComment(pr: PrHandle, body: String): Unit
  def buildStatus(pr: PrHandle): BuildStatus
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration
  ): Either[BuildTimedOut, BuildStatus]
