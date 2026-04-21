package orca

import scala.concurrent.duration.FiniteDuration

case class PrHandle(owner: String, repo: String, number: Int)

case class Comment(author: String, body: String)

enum BuildOutcome derives CanEqual:
  case Success
  case Failure

case class BuildStatus(outcome: BuildOutcome, log: String)

trait GitHubTool:
  def createPr(title: String, body: String): PrHandle
  def readComments(pr: PrHandle): List[Comment]
  def writeComment(pr: PrHandle, body: String): Unit
  def buildStatus(pr: PrHandle): BuildStatus
  def waitForBuild(pr: PrHandle, timeout: FiniteDuration): BuildStatus
