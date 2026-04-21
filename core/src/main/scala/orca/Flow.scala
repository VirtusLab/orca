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

def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => IgnoredIssues,
    maxIterations: Int = 10
)(using FlowContext): IgnoredIssues = ???

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
