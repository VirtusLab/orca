package orca.plan

import orca.{FlowContext, FlowControl, InStage, WorkspaceWrite, fail, session}
import orca.agents.Agent

/** Have `agent` write a test at `testPath` that fails the way `request`
  * describes, then confirm it with a separate check. The writer retries once
  * when the check disagrees; a second disagreement fails the stage, so a re-run
  * starts it over.
  */
def reproduceBug(request: String, testPath: String, agent: Agent[?])(using
    FlowContext,
    FlowControl,
    InStage,
    WorkspaceWrite
): Unit =
  val reproducer = agent.session("reproducer", seed = request)
  val _ = reproducer.run(s"${PlanPrompts.Reproduce}\n\nTest path: `$testPath`")
  val first =
    checkReproduction(request = request, testPath = testPath, agent = agent)
  if !first.matches then
    val _ = reproducer.run(
      s"""${PlanPrompts.ReproduceRetry}
         |
         |Test path: `$testPath`
         |
         |The check's explanation:
         |${first.explanation}""".stripMargin
    )
    val second =
      checkReproduction(request = request, testPath = testPath, agent = agent)
    if !second.matches then
      fail(
        s"Could not reproduce the request: ${second.explanation}. Add " +
          "reproduction details to the request (or the issue) and re-run; " +
          "the stage starts over."
      )

/** Full tier: a wrong "matches" lets a bogus reproduction through, a wrong
  * "doesn't" aborts a sound one.
  */
private def checkReproduction(
    request: String,
    testPath: String,
    agent: Agent[?]
)(using ctx: FlowContext, ev: InStage): BugReportMatch =
  val testHint = ctx.stackSettings.test match
    case Nil => ""
    case commands =>
      s"\nThe project's test command: ${commands.mkString(" && ")}"
  agent
    .resultAs[BugReportMatch]
    .autonomous
    .run(
      s"""${PlanPrompts.ReproductionCheck}
         |
         |Test path: `$testPath`$testHint
         |
         |Request:
         |$request""".stripMargin
    )
