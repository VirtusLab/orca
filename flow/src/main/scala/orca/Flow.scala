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
      val msg = Option(e.getMessage)
        .getOrElse(e.getClass.getName)
        .linesIterator
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

/** Outcome of a single review/fix iteration. Consumed by `fixLoop`'s recursive
  * driver and by `closingMessage` (via the bail kind) for the final summary.
  *
  * `AllIgnored` covers the "fixer says it can't address any of these" case —
  * common when the reviewer flagged something environmental like a missing CLI.
  * There's no value in re-evaluating, so the loop halts and the discarded
  * issues fall into the closing summary.
  */
private enum IterationOutcome:
  case Clean
  case Progressed(newlyIgnored: IgnoredIssues)
  case AllIgnored(newlyIgnored: IgnoredIssues)
  case NoProgress(remaining: List[ReviewIssue])
  case Capped(remaining: List[ReviewIssue], max: Int)

private enum BailKind:
  case MaxIterations(max: Int)
  case NoProgress

/** Evaluate, fix, re-evaluate until the reviewer reports only issues that are
  * already in the caller's "ignored" set, `fix` makes no progress, every
  * remaining issue is marked as won't-fix, or `maxIterations` fix attempts have
  * been made. Remaining issues after a bail-out are folded into the returned
  * IgnoredIssues with a reason so callers can surface them.
  *
  * Each call to `evaluate` is rendered as a nested stage (`Iteration N`) so the
  * status bar's breadcrumb shows where we are. Inside the stage: found-issues
  * summary, one step per issue, then the iteration's closing line (`Fixed
  * review comments` / `Unable to fix review comments` / `All N marked as
  * won't-fix` / `No review comments`).
  *
  * `maxIterations` counts fix attempts — the cap-reaching iteration runs
  * `evaluate` one last time but skips `fix`. Callers that want to surface
  * "Running N review agents" (or anything before evaluate) emit it themselves
  * from inside the supplied `evaluate` closure.
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => IgnoredIssues,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =

  def emitStep(msg: String): Unit = ctx.emit(OrcaEvent.Step(msg))

  /** One full iteration: open the stage, evaluate, optionally fix, emit the
    * per-iteration summary line, return a structured outcome. The cap check
    * lives here so every `evaluate` call (including the final one) is uniformly
    * framed as `Iteration N` — there's no separate "post-cap" code path the
    * reader has to special-case.
    *
    * Callers (e.g. `reviewAndFixLoop`) emit their own "Running N agents" step
    * from inside `evaluate`, so this function stays domain-agnostic — no
    * agentCount knob.
    */
  def runIteration(
      iteration: Int,
      ignoredSet: Set[ReviewIssue]
  ): IterationOutcome =
    stage(s"Iteration ${iteration + 1}"):
      val remaining = evaluate().issues.filterNot(ignoredSet.contains)
      // Per-issue surfacing happens inside `evaluate` — the caller
      // formats each reviewer's outcome with provenance there. The
      // iteration body just drives the fix decision.
      if remaining.isEmpty then
        emitStep("No review comments")
        IterationOutcome.Clean
      else if iteration >= maxIterations then
        emitStep(s"Reached max iterations ($maxIterations); bailing out")
        IterationOutcome.Capped(remaining, maxIterations)
      else
        val newlyIgnored = fix(remaining)
        if newlyIgnored.issues.isEmpty then
          emitStep("Unable to fix review comments")
          IterationOutcome.NoProgress(remaining)
        else if newlyIgnored.issues.size >= remaining.size then
          // Agent claimed nothing was actually fixed in code —
          // every remaining issue came back as ignored. Re-running
          // reviewers would just rediscover the same things; halt.
          emitStep(
            s"All ${pluralize(remaining.size, "review comment")} marked as won't-fix"
          )
          IterationOutcome.AllIgnored(newlyIgnored)
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
      case IterationOutcome.AllIgnored(newlyIgnored) =>
        // Clean exit with `bail = None` — the agent disclaimed the
        // remaining issues by domain decision, so the closing line
        // is "Discarded N", not "Bailed out".
        (accumulated ++ newlyIgnored, None)
      case IterationOutcome.NoProgress(remaining) =>
        (
          accumulated ++ capReason(remaining, "fix made no progress"),
          Some(BailKind.NoProgress)
        )
      case IterationOutcome.Capped(remaining, max) =>
        (
          accumulated ++ capReason(remaining, s"max iterations ($max) reached"),
          Some(BailKind.MaxIterations(max))
        )

  val (result, bail) = loop(IgnoredIssues(Nil), Set.empty, 0)
  // Closing summary when issues remain — bail-out paths and
  // domain-meaningful "won't fix" decisions both populate
  // `result.issues`. The all-clean case is already surfaced as
  // "No review comments" inside the final iteration's stage, so we
  // skip the closing line there to avoid duplicating the same note.
  if result.issues.nonEmpty then emitStep(closingMessage(result, bail))
  result

/** Format a single review comment as the body lines of a `Step`.
  *
  * Shape: `- [Severity] shortSummary ...wrapped to ~76 cols...`, optionally
  * followed by ` at file:line` and a ` suggestion: …` line. The leading `- `
  * makes the issue a bullet within a multi- issue body; outer indentation is
  * added by the caller (typically [[formatReviewerOutcome]]).
  *
  * The `description` field is intentionally not rendered — it's the longer form
  * fed back to the fixing agent; the user sees the short form on screen.
  */
private[orca] def formatIssue(issue: ReviewIssue): String =
  val header = TextWrap.wrap(
    s"- [${issue.severity}] ${issue.shortSummary}",
    maxWidth = 74,
    continuation = "  "
  )
  val location = (issue.file, issue.line) match
    case (Some(f), Some(l)) => Some(s"    at $f:$l")
    case (Some(f), None)    => Some(s"    at $f")
    case _                  => None
  val suggestion = issue.suggestion.map: s =>
    TextWrap.wrap(s"    suggestion: $s", maxWidth = 74, continuation = "      ")
  List(Some(header), location, suggestion).flatten.mkString("\n")

/** Format a reviewer's outcome as a `▶`-step body — heading line names the
  * reviewer + issue count, then bulleted issue details indented under it. Clean
  * reviews collapse to a single "<name>: 0 issues" line.
  */
private[orca] def formatReviewerOutcome(
    reviewerName: String,
    result: ReviewResult
): String =
  if result.issues.isEmpty then s"$reviewerName: 0 issues"
  else
    val header = s"$reviewerName: ${pluralize(result.issues.size, "issue")}"
    val bullets = result.issues.map(formatIssue).mkString("\n")
    s"$header\n$bullets"

/** Final summary line, only emitted when `result.issues` is non-empty. The
  * clean-exit path is surfaced as `Step("No review comments")` inside the last
  * iteration's stage; reaching this function means either the loop bailed
  * (explicit `BailKind`) or `fix` returned domain-meaningful "won't fix"
  * reasons across iterations.
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

/** Pluralize an English noun by appending "s" when `n != 1`. The same count
  * goes into the rendered string (`"1 review comment"` / `"3 review
  * comments"`), so this also encodes the count. Centralised here so
  * iteration-stage steps and the closing summary stay consistent in wording.
  */
private[orca] def pluralize(n: Int, singular: String): String =
  s"$n $singular${if n == 1 then "" else "s"}"

private def capReason(
    issues: List[ReviewIssue],
    reason: String
): IgnoredIssues =
  IgnoredIssues(issues.map(IgnoredIssue(_, reason)))

/** One round of reviews, with each reviewer's individual outcome preserved. The
  * list keeps the order callers configured (so positions match
  * `defaultReviewers(claude)` etc.), and lets the loop decide which reviewers
  * to re-run on the next iteration based on which ones found issues this time.
  */
case class ReviewBatch(outcomes: List[(LlmTool[?], ReviewResult)]):
  def reviewersWithIssues: List[LlmTool[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
  def allIssues: List[ReviewIssue] =
    outcomes.flatMap(_._2.issues)

/** Picks which reviewers to run on each iteration of [[reviewAndFixLoop]].
  * `history` holds prior batches with the most recent first; `all` is the
  * originally configured set, useful for the very first iteration when there's
  * no history yet.
  */
type ReviewerSelector =
  (history: List[ReviewBatch], all: List[LlmTool[?]]) => List[LlmTool[?]]

object ReviewerSelector:

  /** Default. First iteration runs every reviewer; subsequent rounds re-run
    * only those that found something last round. Saves API spend on
    * consistently-quiet reviewers; the trade-off is that a reviewer who'd catch
    * a regression introduced by a fix won't see the fix.
    */
  val onlyChangedDimensions: ReviewerSelector = (history, all) =>
    history.headOption match
      case None        => all
      case Some(batch) => batch.reviewersWithIssues

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = (_, all) => all

private case class FixRequest(
    instructions: String,
    issues: List[ReviewIssue]
) derives JsonData

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * any issues above `confidenceThreshold` to `coder` via `continueSession`, and
  * loop. Each iteration's reviewer set is picked by `reviewerSelection`; the
  * default re-runs only reviewers that found issues last round.
  *
  * The fix step instructs the agent explicitly: fix what you can, mark anything
  * else (environmental, out-of-scope, false positive) as ignored with a reason.
  * When the agent marks every remaining issue as ignored, the loop halts
  * cleanly — there's no point re-evaluating something the agent has already
  * disclaimed.
  */
def reviewAndFixLoop[B <: Backend](
    coder: LlmTool[B],
    sessionId: SessionId[B],
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String] = None,
    confidenceThreshold: Double = 0.7,
    reviewerSelection: ReviewerSelector =
      ReviewerSelector.onlyChangedDimensions,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =
  // Threaded across iterations via the closure: each evaluate appends
  // its batch and the selector reads back over the list. Method-scope
  // var allowed by the project's FP conventions; the loop is single-
  // threaded so visibility isn't a concern.
  var history: List[ReviewBatch] = Nil

  def evaluate(): ReviewResult =
    val active = reviewerSelection(history, reviewers)
    val totalAgents = active.size + (if lintCommand.isDefined then 1 else 0)
    if totalAgents > 0 then
      ctx.emit(
        OrcaEvent.Step(s"Running ${pluralize(totalAgents, "review agent")}")
      )
    val reviewerOutcomes: List[(LlmTool[?], ReviewResult)] =
      supervised:
        active
          .map(r => r -> fork(r.resultAs[ReviewResult].autonomous(task)))
          .map((r, f) => r -> f.join())
    val batch = ReviewBatch(reviewerOutcomes)
    history = batch :: history
    // Surface each reviewer's outcome with provenance: agent name +
    // count + per-issue summary, location, and suggestion. The
    // per-call `StructuredResult` event is silenced via
    // `Announce[ReviewResult] = ""`; this is the named replacement.
    reviewerOutcomes.foreach: (reviewer, result) =>
      ctx.emit(OrcaEvent.Step(formatReviewerOutcome(reviewer.name, result)))
    val lintResult =
      lintCommand.toList.map(cmd => "lint" -> lint(cmd, claude.haiku))
    lintResult.foreach: (name, result) =>
      ctx.emit(OrcaEvent.Step(formatReviewerOutcome(name, result)))
    val all = batch.allIssues ++ lintResult.flatMap(_._2.issues)
    val kept = all.filter(_.confidence >= confidenceThreshold)
    ReviewResult(
      issues = kept,
      summary =
        s"${kept.size} issue(s) at or above confidence $confidenceThreshold"
    )

  def fix(issues: List[ReviewIssue]): IgnoredIssues =
    coder
      .resultAs[IgnoredIssues]
      .continueSession(
        sessionId,
        FixRequest(FixInstructions, issues),
        LlmConfig.default
      )

  // The stage doesn't repeat `task` in its label — the enclosing
  // implement-task stage already names it.
  stage("Review & fix"):
    fixLoop(
      evaluate = () => evaluate(),
      fix = fix,
      maxIterations = maxIterations
    )

/** Prompt sent alongside `FixRequest` so the fixing agent knows what the loop
  * expects: fix in code where possible, otherwise mark as ignored with a brief
  * reason. Without this guidance the agent tends to re-attempt unfixable items
  * every round (the same "cargo: command not found" comes back from each
  * reviewer because nothing changed in the environment). Marking it as ignored
  * once lets the loop exit cleanly via the `AllIgnored` outcome.
  */
private val FixInstructions: String =
  """For each review comment below: fix it directly in the codebase
    |if you can. Otherwise — when the issue is environmental (missing
    |tooling, network), out of scope for this task, or you assess it
    |as a false positive — include it in the IgnoredIssues response
    |with a brief reason. Don't include items you actually fixed.
    |
    |If every comment is environmental or out of scope, return all of
    |them as IgnoredIssues with reasons; the loop will halt instead of
    |asking the same reviewers again.""".stripMargin

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
