package orca.review

import orca.{
  Backend,
  FlowContext,
  JsonData,
  LlmConfig,
  LlmTool,
  OrcaEvent,
  SessionId,
  Title,
  given
}
import orca.io.TextWrap
import ox.par

/** What the fixing agent reports back per iteration: the titles of issues it
  * actually fixed in the code, and the issues it chose not to fix along with a
  * reason. The prompt requires every input issue to land in exactly one list;
  * any title showing up in neither is silently dropped by the loop.
  */
case class FixOutcome(
    fixed: List[Title],
    ignored: List[IgnoredIssue]
) derives JsonData

/** Evaluate, fix, re-evaluate until the reviewer reports no issues, the fixer
  * reports zero fixes (so re-evaluating would just rediscover the same things),
  * or `maxIterations` fix attempts have been made. Issues that remain when the
  * cap is hit are folded into the returned `IgnoredIssues` with a `max
  * iterations reached` reason so callers can surface them.
  *
  * Each call to `evaluate` runs inside a nested `Iteration N` stage.
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => FixOutcome,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =

  def emitStep(msg: String): Unit = ctx.emit(OrcaEvent.Step(msg))

  // Either keep going (and accumulate ignored entries) or stop. The Stop
  // variant carries any final additions to the accumulator.
  enum Step:
    case Continue(addIgnored: IgnoredIssues)
    case Stop(addIgnored: IgnoredIssues)

  def runIteration(iteration: Int): Step =
    orca.stage(s"Iteration ${iteration + 1}"):
      val issues = evaluate().issues
      if issues.isEmpty then
        emitStep("No review comments")
        Step.Stop(IgnoredIssues(Nil))
      else if iteration >= maxIterations then
        emitStep(s"Reached max iterations ($maxIterations); bailing out")
        Step.Stop(
          IgnoredIssues(
            issues.map(i =>
              IgnoredIssue(i.title, s"max iterations ($maxIterations) reached")
            )
          )
        )
      else
        val outcome = fix(issues)
        emitStep(
          s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}"
        )
        if outcome.fixed.isEmpty then Step.Stop(IgnoredIssues(outcome.ignored))
        else Step.Continue(IgnoredIssues(outcome.ignored))

  @scala.annotation.tailrec
  def loop(accumulated: IgnoredIssues, iteration: Int): IgnoredIssues =
    runIteration(iteration) match
      case Step.Stop(add)     => accumulated ++ add
      case Step.Continue(add) => loop(accumulated ++ add, iteration + 1)

  loop(IgnoredIssues(Nil), 0)

/** Format a single review comment as the body lines of a `Step`.
  *
  * Shape: `- [Severity] title ...wrapped to ~76 cols...`, optionally followed
  * by ` at file:line` and a ` suggestion: …` line. The leading `- ` makes the
  * issue a bullet within a multi-issue body; outer indentation is added by the
  * caller (typically [[formatReviewerOutcome]]).
  *
  * The `description` field is intentionally not rendered — it's the longer form
  * fed back to the fixing agent; the user sees the short form on screen.
  */
private[review] def formatIssue(issue: ReviewIssue): String =
  val header = TextWrap.wrap(
    s"- [${issue.severity}] ${issue.title}",
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
private[review] def formatReviewerOutcome(
    reviewerName: String,
    result: ReviewResult
): String =
  if result.issues.isEmpty then s"$reviewerName: 0 issues"
  else
    val header =
      s"$reviewerName: ${orca.pluralize(result.issues.size, "issue")}"
    val bullets = result.issues.map(formatIssue).mkString("\n")
    s"$header\n$bullets"

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
  val onlyPreviouslyReporting: ReviewerSelector = (history, all) =>
    history.headOption match
      case None        => all
      case Some(batch) => batch.reviewersWithIssues

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = (_, all) => all

  /** Asks `llm` to pick which reviewers are worth running for a given task. The
    * selection is computed on the first call (when `all` is known) and cached
    * for subsequent iterations — `taskTitle` and `changedFiles` don't change
    * mid-loop, so re-querying the model would just burn tokens for the same
    * answer.
    *
    * Pick a cheap model (e.g. `claude.haiku`); the request is small. Override
    * `instructions` to retune the selection brief.
    */
  def llmDriven(
      llm: LlmTool[?],
      taskTitle: Title,
      changedFiles: List[String],
      instructions: String = ReviewLoopPrompts.SelectReviewers
  ): ReviewerSelector =
    var cached: Option[List[String]] = None
    (_, all) =>
      val names = cached.getOrElse:
        val picked = llm
          .resultAs[SelectedReviewers]
          .autonomous
          .run(
            ReviewerSelectionRequest(
              taskTitle = taskTitle,
              changedFiles = changedFiles,
              availableReviewers = all.map(_.name),
              instructions = instructions
            )
          )
          .names
        cached = Some(picked)
        picked
      SelectedReviewers(names).pick(all)

private case class ReviewerSelectionRequest(
    taskTitle: Title,
    changedFiles: List[String],
    availableReviewers: List[String],
    instructions: String
) derives JsonData

private case class FixRequest(
    instructions: String,
    issues: List[ReviewIssue]
) derives JsonData

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * any issues above `confidenceThreshold` to `coder` via `continueSession`, and
  * loop. Each iteration's reviewer set is picked by `reviewerSelection`; the
  * default re-runs only reviewers that found issues last round.
  *
  * The fix step instructs the agent to report a `FixOutcome`: list the titles
  * of issues actually fixed in code under `fixed`, and anything not addressed
  * (environmental, out-of-scope, false positive) under `ignored` with a reason.
  * The loop only re-evaluates when something was fixed — if `fixed` is empty
  * there's nothing new for the reviewers to find, so the loop halts.
  */
def reviewAndFixLoop[B <: Backend](
    coder: LlmTool[B],
    sessionId: SessionId[B],
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String] = None,
    /** LLM that summarises lint output into a `ReviewResult`. Required when
      * `lintCommand` is set; ignored otherwise. Use a cheap model
      * (`claude.haiku`, `codex.mini`) — the lint summary is a small fold.
      */
    lintLlm: Option[LlmTool[?]] = None,
    confidenceThreshold: Double = 0.7,
    reviewerSelection: ReviewerSelector =
      ReviewerSelector.onlyPreviouslyReporting,
    maxIterations: Int = 10,
    fixInstructions: String = ReviewLoopPrompts.Fix
)(using ctx: FlowContext): IgnoredIssues =
  require(
    lintCommand.isEmpty || lintLlm.isDefined,
    "reviewAndFixLoop: lintCommand requires lintLlm"
  )
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
        OrcaEvent.Step(
          s"Running ${orca.pluralize(totalAgents, "review agent")}"
        )
      )
    val reviewerOutcomes: List[(LlmTool[?], ReviewResult)] =
      par(
        active.map(r =>
          () => r -> r.resultAs[ReviewResult].autonomous.run(task)
        )
      ).toList
    val batch = ReviewBatch(reviewerOutcomes)
    history = batch :: history
    // Surface each reviewer's outcome with provenance.
    reviewerOutcomes.foreach: (reviewer, result) =>
      ctx.emit(OrcaEvent.Step(formatReviewerOutcome(reviewer.name, result)))
    val lintResult =
      lintCommand
        .zip(lintLlm)
        .toList
        .map: (cmd, llm) =>
          "lint" -> lint(cmd, llm)
    lintResult.foreach: (name, result) =>
      ctx.emit(OrcaEvent.Step(formatReviewerOutcome(name, result)))
    val all = batch.allIssues ++ lintResult.flatMap(_._2.issues)
    val kept = all.filter(_.confidence >= confidenceThreshold)
    ReviewResult(issues = kept)

  def fix(issues: List[ReviewIssue]): FixOutcome =
    coder
      .resultAs[FixOutcome]
      .autonomous
      .continueSession(
        sessionId,
        FixRequest(fixInstructions, issues),
        LlmConfig.default
      )

  // The stage doesn't repeat `task` in its label — the enclosing
  // implement-task stage already names it.
  orca.stage("Review & fix"):
    fixLoop(
      evaluate = () => evaluate(),
      fix = fix,
      maxIterations = maxIterations
    )

/** Run `command` via a login shell, capture both stdout and stderr, and hand
  * the combined output to `llm` to summarize as a `ReviewResult`. An empty
  * output short-circuits to `ReviewResult.empty` so clean runs skip the
  * round-trip to the LLM. Override `instructions` when the lint produces
  * unusual shapes the default phrasing doesn't fit.
  */
def lint(
    command: String,
    llm: LlmTool[?],
    instructions: String = ReviewLoopPrompts.SummarizeLint
)(using FlowContext): ReviewResult =
  val proc = os
    .proc("bash", "-c", command)
    .call(check = false, mergeErrIntoOut = true)
  val output = proc.out.text().trim
  if output.isEmpty then ReviewResult.empty
  else
    llm
      .resultAs[ReviewResult]
      .autonomous
      .run(s"$instructions\n\nLint output:\n$output")
