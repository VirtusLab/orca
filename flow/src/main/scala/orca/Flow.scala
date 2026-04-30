package orca

import orca.io.TextWrap
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

/** Outcome of a single review/fix iteration. Consumed by `fixLoop`'s
  * recursive driver and by `closingMessage` (via the bail kind) for
  * the final summary.
  */
private enum IterationOutcome:
  case Clean
  case Progressed(newlyIgnored: IgnoredIssues)
  case NoProgress(remaining: List[ReviewIssue])
  case Capped(remaining: List[ReviewIssue], max: Int)

private enum BailKind:
  case MaxIterations(max: Int)
  case NoProgress

/** Evaluate, fix, re-evaluate until the reviewer reports only issues that are
  * already in the caller's "ignored" set, `fix` makes no progress, or
  * `maxIterations` fix attempts have been made. Remaining issues after the loop
  * bails out are folded into the returned IgnoredIssues with a reason so
  * callers can surface them to users.
  *
  * Each call to `evaluate` is rendered as a nested stage (`Iteration N`) so
  * the status bar's breadcrumb shows where we are in the loop. Inside the
  * stage the sequence is: a `Running N review agents` step (when
  * `agentCount > 0`), the found-issues summary, one step per issue, then
  * the iteration's closing line (`Fixed review comments` / `Unable to fix
  * review comments` / `No review comments`).
  *
  * `maxIterations` counts fix attempts — the cap-reaching iteration runs
  * `evaluate` one last time to see what's still outstanding but skips
  * `fix`. `agentCount` is purely cosmetic (drives the "Running N" step)
  * and defaults to 0 for callers that don't want that line.
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => IgnoredIssues,
    maxIterations: Int = 10,
    agentCount: Int = 0
)(using ctx: FlowContext): IgnoredIssues =

  def emitStep(msg: String): Unit = ctx.emit(OrcaEvent.Step(msg))

  /** One full iteration: open the stage, evaluate, optionally fix,
    * emit the per-iteration summary line, return a structured outcome.
    * The cap check lives here so every `evaluate` call (including the
    * final one) is uniformly framed as `Iteration N` — there's no
    * separate "post-cap" code path the reader has to special-case.
    */
  def runIteration(
      iteration: Int,
      ignoredSet: Set[ReviewIssue]
  ): IterationOutcome =
    stage(s"Iteration ${iteration + 1}"):
      if agentCount > 0 then
        emitStep(s"Running ${pluralize(agentCount, "review agent")}")
      val remaining = evaluate().issues.filterNot(ignoredSet.contains)
      if remaining.isEmpty then
        emitStep("No review comments")
        IterationOutcome.Clean
      else
        emitStep(s"Found ${pluralize(remaining.size, "review comment")}")
        // Surface each comment in the event log before handing them
        // to `fix`. Without this the user only sees the count and
        // has to dig into the agent transcript to learn what was
        // actually flagged.
        remaining.foreach: issue =>
          emitStep(formatIssue(issue))
        if iteration >= maxIterations then
          emitStep(s"Reached max iterations ($maxIterations); bailing out")
          IterationOutcome.Capped(remaining, maxIterations)
        else
          val newlyIgnored = fix(remaining)
          if newlyIgnored.issues.isEmpty then
            emitStep("Unable to fix review comments")
            IterationOutcome.NoProgress(remaining)
          else
            emitStep("Fixed review comments")
            IterationOutcome.Progressed(newlyIgnored)

  @scala.annotation.tailrec
  def loop(
      accumulated: IgnoredIssues,
      ignoredSet: Set[ReviewIssue],
      iteration: Int
  ): (IgnoredIssues, Option[BailKind]) =
    runIteration(iteration, ignoredSet) match
      case IterationOutcome.Clean =>
        (accumulated, None)
      case IterationOutcome.Progressed(newlyIgnored) =>
        loop(
          accumulated ++ newlyIgnored,
          ignoredSet ++ newlyIgnored.issues.map(_.issue),
          iteration + 1
        )
      case IterationOutcome.NoProgress(remaining) =>
        (accumulated ++ capReason(remaining, "fix made no progress"),
         Some(BailKind.NoProgress))
      case IterationOutcome.Capped(remaining, max) =>
        (accumulated ++ capReason(remaining, s"max iterations ($max) reached"),
         Some(BailKind.MaxIterations(max)))

  val (result, bail) = loop(IgnoredIssues(Nil), Set.empty, 0)
  // Closing summary when issues remain — bail-out paths and
  // domain-meaningful "won't fix" decisions both populate
  // `result.issues`. The all-clean case is already surfaced as
  // "No review comments" inside the final iteration's stage, so we
  // skip the closing line there to avoid duplicating the same note.
  if result.issues.nonEmpty then
    emitStep(closingMessage(result, bail))
  result

/** Format a single review comment as a multi-line `Step` body.
  *
  * Shape: `[Severity] description ...wrapped to ~76 cols...`,
  * optionally followed by `at file:line` and a `suggestion: …` line,
  * each on their own line indented two spaces (under the description's
  * first character once the renderer prepends the `▶ ` glyph). The
  * description wraps at 74 cols with the same 2-space hanging indent
  * so wrapped lines align with location/suggestion lines.
  */
private[orca] def formatIssue(issue: ReviewIssue): String =
  val header = TextWrap.wrap(
    s"[${issue.severity}] ${issue.description}",
    maxWidth = 74,
    continuation = "  "
  )
  val location = (issue.file, issue.line) match
    case (Some(f), Some(l)) => Some(s"  at $f:$l")
    case (Some(f), None)    => Some(s"  at $f")
    case _                  => None
  val suggestion = issue.suggestion.map: s =>
    TextWrap.wrap(s"  suggestion: $s", maxWidth = 74, continuation = "    ")
  List(Some(header), location, suggestion).flatten.mkString("\n")

/** Final summary line, only emitted when `result.issues` is non-empty.
  * The clean-exit path is surfaced as `Step("No review comments")`
  * inside the last iteration's stage; reaching this function means
  * either the loop bailed (explicit `BailKind`) or `fix` returned
  * domain-meaningful "won't fix" reasons across iterations.
  */
private def closingMessage(
    result: IgnoredIssues,
    bail: Option[BailKind]
): String =
  val count = pluralize(result.issues.size, "review comment")
  bail match
    case Some(BailKind.MaxIterations(max)) =>
      s"Bailed out with $count unresolved (max iterations ($max) reached)"
    case Some(BailKind.NoProgress) =>
      s"Bailed out with $count unresolved (fix made no progress)"
    case None =>
      s"Discarded $count"

/** Pluralize an English noun by appending "s" when `n != 1`. The same
  * count goes into the rendered string (`"1 review comment"` /
  * `"3 review comments"`), so this also encodes the count. Centralised
  * here so iteration-stage steps and the closing summary stay
  * consistent in wording.
  */
private[orca] def pluralize(n: Int, singular: String): String =
  s"$n $singular${if n == 1 then "" else "s"}"

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
  // The stage doesn't repeat `task` in its label — the enclosing
  // implement-task stage already names it. `agentCount` is the
  // reviewer fan-out plus an optional lint pass, surfaced in each
  // iteration's "Running N review agents" step.
  val agentCount = reviewers.size + (if lintCommand.isDefined then 1 else 0)
  stage("Review & fix"):
    fixLoop(
      evaluate =
        () => gatherReviews(reviewers, task, lintCommand, confidenceThreshold),
      fix = issues =>
        coder
          .resultAs[IgnoredIssues]
          .continueSession(sessionId, FixRequest(issues), LlmConfig.default),
      agentCount = agentCount
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
