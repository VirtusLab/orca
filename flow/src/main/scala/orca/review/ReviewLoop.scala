package orca.review

import orca.{FlowContext}
import orca.plan.Title
import orca.llm.{BackendTag, JsonData, LlmConfig, LlmTool, SessionId, given}
import orca.events.OrcaEvent

import orca.util.TextWrap
import ox.flow.Flow

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
  * `allReviewers(claude)` etc.), and lets the loop decide which reviewers to
  * re-run on the next iteration based on which ones found issues this time.
  */
case class ReviewBatch(outcomes: List[(LlmTool[?], ReviewResult)]):
  def reviewersWithIssues: List[LlmTool[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
  def allIssues: List[ReviewIssue] =
    outcomes.flatMap(_._2.issues)

private case class FixRequest(
    instructions: String,
    issues: List[ReviewIssue]
) derives JsonData

/** All cross-iteration state for `reviewAndFixLoop`, in one immutable record.
  * `history` is consulted by [[ReviewerSelector]]; `sessions` maps a reviewer's
  * name to the opaque `SessionId` from its first `startSession` call. The
  * stored value is tag-erased (`SessionId.Untyped`) because different reviewers
  * may run on different backends — recover the concrete `SessionId[RB]` with
  * `.as[RB]` at read time, keyed by reviewer name. See [[reviewWithSession]]
  * for the invariant that makes the recovery safe.
  */
private case class ReviewLoopState(
    history: List[ReviewBatch],
    sessions: Map[String, SessionId.Untyped]
)
private object ReviewLoopState:
  val empty: ReviewLoopState = ReviewLoopState(Nil, Map.empty)

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * any issues above `confidenceThreshold` to `coder` via `continueSession`, and
  * loop. Each iteration's reviewer set is picked by `reviewerSelection`; the
  * default uses `selectionLlm` (or `coder` if unset) as a cheap LLM picker
  * that narrows the supplied reviewer list to just the dimensions relevant
  * to this task (see [[ReviewerSelector.llmDriven]]). Pass
  * `Some(ReviewerSelector.onlyPreviouslyReporting)` for the previous
  * fixed-rule behaviour, or `Some(ReviewerSelector.allEveryRound)` to skip
  * selection entirely.
  *
  * The fix step instructs the agent to report a `FixOutcome`: list the titles
  * of issues actually fixed in code under `fixed`, and anything not addressed
  * (environmental, out-of-scope, false positive) under `ignored` with a reason.
  * The loop only re-evaluates when something was fixed — if `fixed` is empty
  * there's nothing new for the reviewers to find, so the loop halts.
  */
def reviewAndFixLoop[B <: BackendTag](
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
    /** LLM used for the default LLM-driven reviewer selection. Falls back to
      * `coder` when unset; pass a cheap model (`claude.haiku`, `codex.mini`)
      * to avoid spending expensive coder tokens on the small selection
      * prompt. Ignored when `reviewerSelection` is supplied.
      */
    selectionLlm: Option[LlmTool[?]] = None,
    confidenceThreshold: Double = 0.7,
    /** How the active reviewer set is chosen each iteration. `None`
      * (default) builds an [[ReviewerSelector.llmDriven]] selector wired
      * against `selectionLlm` (or `coder`), `task`, and the file paths
      * extracted from the initial diff. Pass `Some(...)` to override.
      */
    reviewerSelection: Option[ReviewerSelector] = None,
    maxIterations: Int = 10,
    fixInstructions: String = ReviewLoopPrompts.Fix,
    /** Override the diff handed to each reviewer in its initial prompt.
      * Defaults to `ctx.git.diff()` re-sampled at the start of every iteration:
      * a reviewer that joins the active set on iteration N (e.g. picked up by
      * an `onlyPreviouslyReporting` selector after N-1 silent rounds) sees the
      * working tree as it stands then, including the fixes from earlier
      * iterations. Reviewers that already have a session simply
      * `continueSession` and don't get the diff again — their session has the
      * original framing. Pass `Some(...)` to pin the diff (tests, or when the
      * change set has already been committed and `git.diff()` would be empty).
      */
    initialDiff: Option[String] = None
)(using ctx: FlowContext): IgnoredIssues =
  require(
    lintCommand.isEmpty || lintLlm.isDefined,
    "reviewAndFixLoop: lintCommand requires lintLlm"
  )
  require(
    reviewers.map(_.name).distinct.size == reviewers.size,
    "reviewAndFixLoop: reviewer names must be unique — " +
      "the per-reviewer session map is keyed by name"
  )
  // Sampled per iteration in `runReviewers`. A constant override skips the
  // git call; the default thunk shells out fresh each iteration so a newly-
  // active reviewer sees the latest diff rather than the loop-start one.
  def sampleDiff(): String = initialDiff.getOrElse(ctx.git.diff())

  // For the default LLM-driven picker we sample the diff once at loop entry
  // and derive file paths so the selector sees real context (title + paths)
  // rather than title-only. The shell-out is the same one the first
  // iteration would do anyway; here we just cache its result.
  val effectiveSelection: ReviewerSelector =
    reviewerSelection.getOrElse:
      val initialPaths = ReviewLoop.extractChangedFiles(sampleDiff())
      ReviewerSelector.llmDriven(
        llm = selectionLlm.getOrElse(coder),
        taskTitle = Title(task),
        changedFiles = initialPaths
      )

  // All loop state lives in one immutable case class threaded through
  // a method-scope `var`. Within an iteration reviewers fan out via
  // `par`, but the parallel forks read a stable snapshot and the
  // var is reassigned exactly once after they all return — so no
  // concurrent mutation, no `mutable.Map`, no `ConcurrentHashMap`.
  var state: ReviewLoopState = ReviewLoopState.empty

  def filterByConfidence(result: ReviewResult): ReviewResult =
    ReviewResult(issues =
      result.issues.filter(_.confidence >= confidenceThreshold)
    )

  /** Run one reviewer iteration against an immutable sessions snapshot. Returns
    * the review result plus, on a reviewer's first call, the new `(name,
    * SessionId)` entry that the caller should fold into the next state. Pure
    * with respect to its inputs — no side effects on shared state — which lets
    * the caller run many of these in parallel.
    *
    * `currentDiff` is the working-tree diff sampled by the caller at the start
    * of this iteration; only consumed on a reviewer's first call. Reviewers
    * with an existing session ignore it and continue from their original
    * framing.
    *
    * The `stored.as[RB]` recovery is sound because `name → backend` is fixed
    * for the lifetime of the loop (enforced by the uniqueness precondition
    * above): the entry retrieved with a given reviewer's `RB` was written under
    * that same `RB`.
    */
  def reviewWithSession[RB <: BackendTag](
      r: LlmTool[RB],
      sessions: Map[String, SessionId.Untyped],
      currentDiff: String
  ): (ReviewResult, Option[(String, SessionId.Untyped)]) =
    val call = r.resultAs[ReviewResult].autonomous
    sessions.get(r.name) match
      case Some(stored) =>
        (call.continueSession(stored.as[RB], ReviewLoopPrompts.ReReview), None)
      case None =>
        val (sid, result) =
          call.startSession(
            ReviewLoopPrompts.initialReview(task, currentDiff)
          )
        (result, Some(r.name -> SessionId.Untyped.from(sid)))

  /** One parallel agent's contribution. The `Reviewer` variant carries the
    * configured tool and any new session entry that needs folding into the loop
    * state; `Lint` carries only its filtered result (no LLM session).
    */
  enum AgentOutcome:
    case Reviewer(
        tool: LlmTool[?],
        result: ReviewResult,
        entry: Option[(String, SessionId.Untyped)]
    )
    case Lint(result: ReviewResult)

  /** Run every active reviewer plus the optional lint summariser concurrently
    * via `Flow.mapParUnordered`, emitting one Step per agent as it finishes.
    * State is a parameter, not a closure capture — the parallel block never
    * reads a moving var. LLM-internal events (`TokensUsed`, `StructuredResult`)
    * emit from fork threads; [[OrcaListener]] requires implementations to be
    * thread-safe.
    *
    * The diff is sampled once per call so all first-time reviewers see the same
    * payload — pre-sampling also avoids redundant shell-outs.
    */
  def runReviewersAndLint(
      active: List[LlmTool[?]],
      currentState: ReviewLoopState
  ): (
      List[(LlmTool[?], ReviewResult)],
      Option[ReviewResult],
      ReviewLoopState
  ) =
    val needsDiff = active.exists(r => !currentState.sessions.contains(r.name))
    val currentDiff = if needsDiff then sampleDiff() else ""

    val reviewerTasks: List[() => AgentOutcome] = active.map: r =>
      () =>
        val (result, entry) =
          reviewWithSession(r, currentState.sessions, currentDiff)
        AgentOutcome.Reviewer(r, filterByConfidence(result), entry)

    val lintTaskOpt: Option[() => AgentOutcome] =
      lintCommand
        .zip(lintLlm)
        .map: (cmd, llm) =>
          () =>
            // Group lint tokens under the same `reviewer: …` prefix as the
            // dimension reviewers; the renamed copy stays local to this call.
            val labelled = llm.withName("reviewer: lint")
            AgentOutcome.Lint(filterByConfidence(lint(cmd, labelled)))

    val tasks = reviewerTasks ++ lintTaskOpt.toList
    if tasks.isEmpty then (Nil, None, currentState)
    else
      val outcomes: List[AgentOutcome] =
        Flow
          .fromIterable(tasks)
          .mapParUnordered(tasks.size)(_.apply())
          .tap:
            case AgentOutcome.Reviewer(r, res, _) =>
              ctx.emit(OrcaEvent.Step(formatReviewerOutcome(r.name, res)))
            case AgentOutcome.Lint(res) =>
              ctx.emit(
                OrcaEvent.Step(formatReviewerOutcome("reviewer: lint", res))
              )
          .runToList()

      val reviewerOutcomes = outcomes.collect:
        case AgentOutcome.Reviewer(r, res, _) => (r, res)
      val lintOutcome = outcomes.collectFirst:
        case AgentOutcome.Lint(res) => res
      val newSessions = outcomes.foldLeft(currentState.sessions):
        case (acc, AgentOutcome.Reviewer(_, _, Some(entry))) => acc + entry
        case (acc, _)                                        => acc
      val nextState = ReviewLoopState(
        history = ReviewBatch(reviewerOutcomes) :: currentState.history,
        sessions = newSessions
      )
      (reviewerOutcomes, lintOutcome, nextState)

  def evaluate(): ReviewResult =
    val active = effectiveSelection(state.history, reviewers)
    val totalAgents = active.size + (if lintCommand.isDefined then 1 else 0)
    if totalAgents > 0 then
      ctx.emit(
        OrcaEvent.Step(
          s"Running ${orca.pluralize(totalAgents, "review agent")}"
        )
      )
    // Apply the confidence filter before display so what's shown matches
    // what the fixer receives — otherwise low-confidence issues are listed
    // per-reviewer but silently dropped from the fix payload.
    val (results, lintResult, nextState) = runReviewersAndLint(active, state)
    state = nextState
    ReviewResult(issues =
      results.flatMap(_._2.issues) ++ lintResult.toList.flatMap(_.issues)
    )

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

private[review] object ReviewLoop:
  /** Parse a unified diff and return the changed file paths (the `b/` side of
    * each `+++ b/<path>` header). Filters out `/dev/null` so deletions don't
    * pollute the list. Order matches first appearance in the diff.
    */
  def extractChangedFiles(diff: String): List[String] =
    val pattern = "(?m)^\\+\\+\\+ b/(.+)$".r
    pattern
      .findAllMatchIn(diff)
      .map(_.group(1))
      .filterNot(_ == "/dev/null")
      .toList
      .distinct

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
