package orca

import ox.{fork, supervised}

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
    case e: OrcaFlowException =>
      // `fail(...)` already emitted its Error; malformed-agent-output
      // carries additional context (what the agent said) that the
      // channel should render. OrcaFlowException with a previously-
      // emitted Error goes through without duplicate emission; the
      // malformed-output subtype gets an event with the raw snippet.
      e match
        case mao: orca.io.MalformedAgentOutputException =>
          ctx.emit(OrcaEvent.Error(formatMalformedOutput(name, mao)))
        case _ => ()
      throw e
    case NonFatal(e) =>
      val msg = Option(e.getMessage).getOrElse(e.getClass.getName).linesIterator
        .nextOption()
        .getOrElse(e.getClass.getName)
      ctx.emit(OrcaEvent.Error(s"Stage '$name' failed: $msg"))
      throw e

private def formatMalformedOutput(
    stage: String,
    e: orca.io.MalformedAgentOutputException
): String =
  val snippet =
    val collapsed = e.rawOutput.replaceAll("\\s+", " ").trim
    if collapsed.length <= 200 then collapsed
    else s"${collapsed.take(200)}…"
  s"""Stage '$stage' failed: agent output didn't parse as structured JSON.
     |  cause:  ${e.shortCause}
     |  agent:  $snippet
     |  hint:   tighten the system prompt to enforce JSON-only, or set
     |          ORCA_DEBUG=1 to see the full response.""".stripMargin

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

private case class FixRequest(issues: List[ReviewIssue]) derives JsonData

/** Run the given reviewers in parallel against `task`, optionally include a
  * lint result, filter issues by `confidenceThreshold`, then hand the remaining
  * issues to `coder` via `continueSession` for repair. Loops via `fixLoop`
  * until reviewers report nothing above the threshold or the default iteration
  * cap is reached.
  */
def reviewAndFixLoop[B <: Backend](
    coder: LlmTool[B],
    sessionId: SessionId[B],
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String] = None,
    confidenceThreshold: Double = 0.7
)(using FlowContext): IgnoredIssues =
  stage(s"Review & fix: $task"):
    fixLoop(
      evaluate =
        () => gatherReviews(reviewers, task, lintCommand, confidenceThreshold),
      fix = issues =>
        coder
          .resultAs[IgnoredIssues]
          .continueSession(sessionId, FixRequest(issues), LlmConfig.default)
    )

/** Run each reviewer in parallel, optionally include the lint summary,
  * concatenate the issues, and keep only those above the confidence threshold.
  * A local `supervised` scope confines the forks so the caller doesn't need
  * `using Ox`.
  */
private def gatherReviews(
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String],
    confidenceThreshold: Double
)(using FlowContext): ReviewResult =
  val reviewResults: List[ReviewResult] =
    supervised:
      reviewers
        .map(r => fork(r.resultAs[ReviewResult].autonomous(task)))
        .map(_.join())
  val lintResults: List[ReviewResult] =
    lintCommand.toList.map(cmd => lint(cmd, claude.haiku))
  val allIssues = (reviewResults ++ lintResults).flatMap(_.issues)
  val kept = allIssues.filter(_.confidence >= confidenceThreshold)
  ReviewResult(
    issues = kept,
    summary =
      s"${kept.size} issue(s) at or above confidence $confidenceThreshold"
  )

/** Run `command` via a login shell, capture both stdout and stderr, and hand
  * the combined output to `llm` to summarize as a `ReviewResult`. An empty
  * output short-circuits to `ReviewResult.empty` so clean runs skip the
  * round-trip to the LLM.
  */
def lint(
    command: String,
    llm: LlmTool[?]
)(using FlowContext): ReviewResult =
  val proc = os
    .proc("bash", "-c", command)
    .call(check = false, mergeErrIntoOut = true)
  val output = proc.out.text().trim
  if output.isEmpty then ReviewResult.empty
  else
    llm
      .resultAs[ReviewResult]
      .autonomous(
        s"""Summarize the following lint output into a ReviewResult. Each
           |distinct issue should produce a ReviewIssue; use reasonable
           |confidence based on how actionable the message is.
           |
           |Lint output:
           |$output
           |""".stripMargin
      )
