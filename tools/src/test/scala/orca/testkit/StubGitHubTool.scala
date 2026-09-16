package orca.testkit

import orca.WorkspaceWrite
import orca.tools.{
  BuildStatus,
  Comment,
  GitHubAvailability,
  GitHubTool,
  Issue,
  IssueHandle,
  PrCreateFailed,
  BuildWaitFailed,
  PrHandle
}

import scala.concurrent.duration.FiniteDuration

/** A `GitHubTool` whose every endpoint throws. A suite reaching one or two of
  * them overrides those and inherits the refusals, so the trait's full surface
  * is listed once — and adding an endpoint costs one edit, not one per suite.
  */
class StubGitHubTool extends GitHubTool:
  protected def nyi(endpoint: String): Nothing =
    throw new NotImplementedError(s"$endpoint is not wired in this stub")

  def availability(): GitHubAvailability = nyi("availability")
  def createPr(title: String, body: String)(using
      WorkspaceWrite
  ): Either[PrCreateFailed, PrHandle] = nyi("createPr")
  def updatePr(pr: PrHandle, title: String, body: String)(using
      WorkspaceWrite
  ): Unit = nyi("updatePr")
  def readIssue(issue: IssueHandle): Issue = nyi("readIssue")
  def readIssueComments(issue: IssueHandle): List[Comment] =
    nyi("readIssueComments")
  def readPrComments(pr: PrHandle): List[Comment] = nyi("readPrComments")
  def writeComment(pr: PrHandle, body: String)(using WorkspaceWrite): Unit =
    nyi("writeComment")
  def writeComment(issue: IssueHandle, body: String)(using
      WorkspaceWrite
  ): Unit = nyi("writeComment")
  def upsertComment(pr: PrHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit = nyi("upsertComment")
  def upsertComment(issue: IssueHandle, marker: String, body: String)(using
      WorkspaceWrite
  ): Unit = nyi("upsertComment")
  def buildStatus(pr: PrHandle): BuildStatus = nyi("buildStatus")
  def waitForBuild(
      pr: PrHandle,
      timeout: FiniteDuration,
      noChecksGrace: FiniteDuration
  ): Either[BuildWaitFailed, BuildStatus] = nyi("waitForBuild")
