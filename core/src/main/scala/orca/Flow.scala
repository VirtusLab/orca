package orca

import scala.util.control.NonFatal

/** Wrap `body` as a named stage, emitting StageStarted before and
  * StageCompleted after successful completion. Non-fatal exceptions from `body`
  * trigger an Error event with the stage name and the exception is re-raised.
  * Fatal errors (OOM, InterruptedException, control throwables) propagate
  * without event emission, as they signal shutdown rather than a stage outcome.
  *
  * A body that calls `fail(...)` already emits its own Error, so
  * OrcaFlowException is re-raised without a second Error event.
  */
def stage[T](name: String)(body: => T)(using ctx: FlowContext): T =
  ctx.emit(OrcaEvent.StageStarted(name))
  try
    val result = body
    ctx.emit(OrcaEvent.StageCompleted(name, result.toString))
    result
  catch
    case e: OrcaFlowException => throw e
    case NonFatal(e) =>
      val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
      ctx.emit(OrcaEvent.Error(s"Stage '$name' failed: $msg"))
      throw e

def fail(message: String)(using ctx: FlowContext): Nothing =
  ctx.emit(OrcaEvent.Error(message))
  throw OrcaFlowException(message)

/** Evaluate, fix, re-evaluate until the reviewer reports only issues that are
  * already in the caller's "ignored" set, `fix` makes no progress, or
  * `maxIterations` fix attempts have been made. Remaining issues after the loop
  * bails out are folded into the returned IgnoredIssues with a reason so
  * callers can surface them to users.
  *
  * `maxIterations` counts fix attempts — the loop may `evaluate` once more than
  * that, since the final re-evaluation is what determines whether the final fix
  * stuck.
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => IgnoredIssues,
    maxIterations: Int = 10
)(using FlowContext): IgnoredIssues =
  @scala.annotation.tailrec
  def loop(
      accumulated: IgnoredIssues,
      ignoredSet: Set[ReviewIssue],
      iteration: Int
  ): IgnoredIssues =
    val remaining = evaluate().issues.filterNot(ignoredSet.contains)
    if remaining.isEmpty then accumulated
    else if iteration >= maxIterations then
      accumulated ++ capReason(
        remaining,
        s"max iterations ($maxIterations) reached"
      )
    else
      val newlyIgnored = fix(remaining)
      if newlyIgnored.issues.isEmpty then
        // Fix neither addressed nor ignored anything: evaluate will return
        // the same issues indefinitely, so bail out now.
        accumulated ++ capReason(remaining, "fix made no progress")
      else
        val addedToSet = newlyIgnored.issues.map(_.issue)
        loop(
          accumulated ++ newlyIgnored,
          ignoredSet ++ addedToSet,
          iteration + 1
        )

  loop(IgnoredIssues(Nil), Set.empty, 0)

private def capReason(
    issues: List[ReviewIssue],
    reason: String
): IgnoredIssues =
  IgnoredIssues(issues.map(IgnoredIssue(_, reason)))

def reviewAndFix[B <: Backend](
    coder: LlmTool[B],
    sessionId: SessionId[B],
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String] = None,
    confidenceThreshold: Double = 0.7
)(using FlowContext): IgnoredIssues = ???

def lint(
    command: String,
    llm: LlmTool[?]
)(using FlowContext): ReviewResult = ???
