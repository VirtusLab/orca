package orca.review

import orca.{FlowContext, InStage}
import orca.plan.Title
import orca.agents.{
  AgentInput,
  BackendTag,
  JsonData,
  AgentConfig,
  Agent,
  SessionId,
  given
}
import orca.events.OrcaEvent

import orca.util.TextWrap
import ox.flow.Flow

/** Evaluate, fix, re-evaluate until the reviewer reports no issues, the fixer
  * reports zero fixes (so re-evaluating would just rediscover the same things),
  * or `maxIterations` fix attempts have been made. Issues that remain when the
  * cap is hit are folded into the returned `IgnoredIssues` with a `max
  * iterations reached` reason so callers can surface them.
  *
  * `maxIterations` counts FIX attempts, not evaluations: the loop bails only
  * once `iteration >= maxIterations`, so it performs up to `maxIterations + 1`
  * evaluations (the extra one is the final round that discovers the cap was
  * reached).
  *
  * Each round emits an `Iteration N` progress marker (a `display`, not a
  * committing stage — it runs under the caller's task stage, ADR 0018 §2.2).
  *
  * This is the state-free entry point: it has no cross-iteration data to
  * thread, so it recurses directly. [[reviewAndFixLoop]] carries the same stop
  * policy in [[ReviewFixLoop.run]], where it additionally threads a
  * [[ReviewLoopState]].
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => FixOutcome,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =
  def emitStep(msg: String): Unit = ctx.emit(OrcaEvent.Step(msg))

  @scala.annotation.tailrec
  def loop(accumulated: IgnoredIssues, iteration: Int): IgnoredIssues =
    // A progress marker, not a committing stage: this runs under the caller's
    // task stage (ADR 0018 §2.2), so it must not open its own stage.
    orca.display(s"Iteration ${iteration + 1}")
    val issues = evaluate().issues
    if issues.isEmpty then
      emitStep("No review comments")
      accumulated
    else if iteration >= maxIterations then
      emitStep(s"Reached max iterations ($maxIterations); bailing out")
      accumulated ++ IgnoredIssues(
        issues.map(i =>
          IgnoredIssue(i.title, s"max iterations ($maxIterations) reached")
        )
      )
    else
      val outcome = fix(issues)
      emitStep(s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}")
      if outcome.fixed.isEmpty then
        accumulated ++ IgnoredIssues(outcome.ignored)
      else loop(accumulated ++ IgnoredIssues(outcome.ignored), iteration + 1)

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
case class ReviewBatch(outcomes: List[(Agent[?], ReviewResult)]):
  def reviewersWithIssues: List[Agent[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
  def allIssues: List[ReviewIssue] =
    outcomes.flatMap(_._2.issues)

private case class FixRequest(
    instructions: String,
    issues: List[ReviewIssue]
) derives JsonData

private object FixRequest:
  given AgentInput[FixRequest] with
    def serialize(r: FixRequest): String =
      val formatted = r.issues.map(formatIssue).mkString("\n")
      s"""${r.instructions}
         |
         |Issues to fix:
         |$formatted""".stripMargin

/** All cross-iteration state for `reviewAndFixLoop`, in one immutable record.
  * `history` is consulted by [[ReviewerSelector]]; `sessions` maps a reviewer's
  * name to the opaque `SessionId` returned by its first `run` call. The stored
  * value is tag-erased (`SessionId.Untyped`) because different reviewers may
  * run on different backends — recover the concrete `SessionId[RB]` with
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
  * any issues above `confidenceThreshold` to `coder` via `run(session =
  * sessionId)`, and loop. `reviewerSelection` decides which reviewers run each
  * iteration — typically [[ReviewerSelector.agentDriven]] wired against a cheap
  * picker LLM; pass [[ReviewerSelector.allEveryRound]] to skip selection
  * entirely.
  *
  * The fix step instructs the agent to report a `FixOutcome`: list the titles
  * of issues actually fixed in code under `fixed`, and anything not addressed
  * (environmental, out-of-scope, false positive) under `ignored` with a reason.
  * The loop only re-evaluates when something was fixed — if `fixed` is empty
  * there's nothing new for the reviewers to find, so the loop halts.
  */
def reviewAndFixLoop[B <: BackendTag](
    coder: Agent[B],
    sessionId: SessionId[B],
    reviewers: List[Agent[?]],
    reviewerSelection: ReviewerSelector,
    task: String,
    /** Shell command run before each review round — after the implementation
      * and after every fix — so reviewers and the lint see formatted code and
      * the committed tree stays formatted. Run via `bash -c`, exit status
      * ignored (a formatter that fails shouldn't abort the review). E.g. `"sbt
      * scalafmtAll"`, `"cargo fmt"`, `"prettier -w ."`.
      */
    formatCommand: Option[String] = None,
    lintCommand: Option[String] = None,
    /** LLM that summarises lint output into a `ReviewResult`. Required when
      * `lintCommand` is set; ignored otherwise. Use a cheap model
      * (`claude.haiku`, `codex.mini`) — the lint summary is a small fold.
      */
    lintAgent: Option[Agent[?]] = None,
    confidenceThreshold: Double = 0.7,
    maxIterations: Int = 10,
    fixInstructions: String = ReviewLoopPrompts.Fix,
    /** Override the diff handed to each reviewer in its initial prompt.
      * Defaults to `ctx.git.diff()` re-sampled at the start of every iteration:
      * a reviewer that joins the active set on iteration N (e.g. picked up by
      * an `onlyPreviouslyReporting` selector after N-1 silent rounds) sees the
      * working tree as it stands then, including the fixes from earlier
      * iterations. Reviewers that already have a session resume it and don't
      * get the diff again — their session has the original framing. Pass
      * `Some(...)` to pin the diff (tests, or when the change set has already
      * been committed and `git.diff()` would be empty).
      */
    initialDiff: Option[String] = None
)(using ctx: FlowContext, ev: InStage): IgnoredIssues =
  new ReviewFixLoop(
    coder = coder,
    sessionId = sessionId,
    reviewers = reviewers,
    reviewerSelection = reviewerSelection,
    task = task,
    formatCommand = formatCommand,
    lintCommand = lintCommand,
    lintAgent = lintAgent,
    confidenceThreshold = confidenceThreshold,
    maxIterations = maxIterations,
    fixInstructions = fixInstructions,
    initialDiff = initialDiff
  ).run()

/** Implementation of [[reviewAndFixLoop]]: one instance per invocation holds
  * the loop-constant configuration as fields, so the per-iteration logic reads
  * top-down as plain methods rather than a stack of nested closures. Construct
  * and call [[run]].
  *
  * All cross-iteration state lives in one immutable [[ReviewLoopState]]
  * threaded explicitly through [[run]] (no captured `var`): within an iteration
  * the reviewers fan out via `Flow.mapParUnordered`, but each fork reads the
  * snapshot it was handed and the next state is computed once after they all
  * return — so no concurrent mutation, no `mutable.Map`, no
  * `ConcurrentHashMap`.
  *
  * See [[reviewAndFixLoop]]'s parameter docs for the full description of each
  * constructor parameter.
  *
  * @param formatCommand
  *   Shell command run before each review round; see `reviewAndFixLoop`'s
  *   parameter docs.
  * @param lintAgent
  *   Summarises lint output into a `ReviewResult`; see `reviewAndFixLoop`'s
  *   parameter docs.
  * @param initialDiff
  *   Override for the reviewers' initial diff; see `reviewAndFixLoop`'s
  *   parameter docs.
  */
private[review] class ReviewFixLoop[B <: BackendTag](
    coder: Agent[B],
    sessionId: SessionId[B],
    reviewers: List[Agent[?]],
    reviewerSelection: ReviewerSelector,
    task: String,
    formatCommand: Option[String],
    lintCommand: Option[String],
    lintAgent: Option[Agent[?]],
    confidenceThreshold: Double,
    maxIterations: Int,
    fixInstructions: String,
    initialDiff: Option[String]
)(using ctx: FlowContext, ev: InStage):
  require(
    lintCommand.isEmpty || lintAgent.isDefined,
    "reviewAndFixLoop: lintCommand requires lintAgent"
  )
  require(
    reviewers.map(_.name).distinct.size == reviewers.size,
    "reviewAndFixLoop: reviewer names must be unique — " +
      "the per-reviewer session map is keyed by name"
  )

  private def emitStep(msg: String): Unit = ctx.emit(OrcaEvent.Step(msg))

  // Sampled per iteration in `runReviewersAndLint`. A constant override skips
  // the git call; the default thunk shells out fresh each iteration so a newly-
  // active reviewer sees the latest diff rather than the loop-start one.
  private def sampleDiff(): String = initialDiff.getOrElse(ctx.git.diff())

  // Loop-constant context handed to the selector on every iteration: the
  // task's title, plus the file paths derived from the diff at loop entry.
  // Sampled here so each iteration's selector call doesn't re-shell-out.
  private val taskTitle: Title = Title(task)
  private val changedFiles: List[String] =
    ReviewLoop.extractChangedFiles(sampleDiff())

  private def filterByConfidence(result: ReviewResult): ReviewResult =
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
    * for the lifetime of the loop: [[resolveAgainstRoster]] maps every selected
    * agent back to its canonical roster instance by slug (so a renamed/rebuilt
    * copy can't deliver a wrong-backend session id), and the roster's
    * slug-uniqueness `require` guarantees the entry retrieved with a given
    * reviewer's `RB` was written under that same `RB`.
    *
    * The LLM run is labelled with the `reviewer: <slug>` cost prefix
    * ([[ReviewerPrompts.NamePrefix]]) so the `TokensUsed` breakdown groups
    * reviewer spend; the session map stays keyed by the BARE slug (`r.name`),
    * which is the reviewer's identity everywhere else.
    */
  private def reviewWithSession[RB <: BackendTag](
      r: Agent[RB],
      sessions: Map[String, SessionId.Untyped],
      currentDiff: String
  ): (ReviewResult, Option[(String, SessionId.Untyped)]) =
    val labelled = r.withName(s"${ReviewerPrompts.NamePrefix}${r.name}")
    val call = labelled.resultAs[ReviewResult].autonomous
    sessions.get(r.name) match
      case Some(stored) =>
        val (_, result) =
          call.run(
            ReviewLoopPrompts.ReReview,
            session = stored.as[RB],
            emitPrompt = false
          )
        (result, None)
      case None =>
        val session = labelled.newSession
        val (sid, result) =
          call.run(
            ReviewLoopPrompts.initialReview(task, currentDiff),
            session = session,
            emitPrompt = false
          )
        (result, Some(r.name -> SessionId.Untyped.from(sid)))

  /** One parallel agent's contribution. The `Reviewer` variant carries the
    * configured tool and any new session entry that needs folding into the loop
    * state; `Lint` carries only its filtered result (no LLM session).
    */
  private enum AgentOutcome:
    case Reviewer(
        tool: Agent[?],
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
  private def runReviewersAndLint(
      active: List[Agent[?]],
      currentState: ReviewLoopState
  ): (
      List[(Agent[?], ReviewResult)],
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
        .zip(lintAgent)
        .map: (cmd, agent) =>
          () =>
            // Group lint tokens under the same `reviewer: …` cost prefix as the
            // dimension reviewers; the renamed copy stays local to this call.
            val labelled = agent.withName(s"${ReviewerPrompts.NamePrefix}lint")
            AgentOutcome.Lint(filterByConfidence(lint(cmd, labelled)))

    val tasks = reviewerTasks ++ lintTaskOpt.toList
    if tasks.isEmpty then (Nil, None, currentState)
    else
      val outcomes: List[AgentOutcome] =
        Flow
          .fromIterable(tasks)
          .mapParUnordered(tasks.size)(_.apply())
          .tap:
            // Display the bare slug — the `reviewer: ` prefix is a cost-report
            // grouping detail, not part of what the user sees per reviewer.
            case AgentOutcome.Reviewer(r, res, _) =>
              ctx.emit(OrcaEvent.Step(formatReviewerOutcome(r.name, res)))
            case AgentOutcome.Lint(res) =>
              ctx.emit(OrcaEvent.Step(formatReviewerOutcome("lint", res)))
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

  /** The selector may return arbitrary agents; only roster members run, and
    * every selection is mapped back to its CANONICAL roster instance by slug —
    * the per-reviewer session map is keyed by slug, so a renamed/rebuilt copy
    * can't deliver a wrong-backend session id. Foreign names are dropped with a
    * visible warning; duplicates collapse to one run. Safety floor: if a
    * non-empty selection resolves to nothing (e.g. a custom selector still
    * using pre-rename `reviewer: <slug>` names), fall back to the full roster —
    * orca's contract is that AI-written code is never silently unreviewed. An
    * empty selection stays empty: that is the loop's designed stop signal.
    */
  private def resolveAgainstRoster(selected: List[Agent[?]]): List[Agent[?]] =
    val byName = reviewers.map(r => r.name -> r).toMap
    val (known, foreign) = selected.partition(a => byName.contains(a.name))
    if foreign.nonEmpty then
      emitStep(
        s"reviewer selection: dropped ${foreign.map(_.name).mkString(", ")} — not in the configured roster"
      )
    val resolved = known.flatMap(a => byName.get(a.name)).distinctBy(_.name)
    if resolved.isEmpty && selected.nonEmpty then
      emitStep(
        s"reviewer selection: nothing resolved against the roster; falling back to all ${reviewers.size} reviewer(s)"
      )
      reviewers
    else resolved

  private def evaluate(
      state: ReviewLoopState,
      selectRound: List[ReviewBatch] => List[Agent[?]]
  ): (ReviewResult, ReviewLoopState) =
    // Format before reviewing so the implementation's (and each prior fix's)
    // edits are cleaned up before reviewers and the lint see them, and the
    // committed tree stays formatted. Exit status ignored — a formatter failure
    // shouldn't abort the review. `mergeErrIntoOut` folds stderr into the
    // captured stdout so neither stream reaches the terminal and tears the
    // status row (previously stdout was captured but stderr leaked through).
    formatCommand.foreach: cmd =>
      val _ =
        os.proc("bash", "-c", cmd).call(check = false, mergeErrIntoOut = true)
    val active = resolveAgainstRoster(selectRound(state.history))
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
    val result = ReviewResult(issues =
      results.flatMap(_._2.issues) ++ lintResult.toList.flatMap(_.issues)
    )
    (result, nextState)

  private def fix(issues: List[ReviewIssue]): FixOutcome =
    coder
      .resultAs[FixOutcome]
      .autonomous
      .run(
        FixRequest(fixInstructions, issues),
        session = sessionId,
        emitPrompt = false
      )
      ._2

  /** Run the evaluate/fix loop to convergence and return the accumulated
    * [[IgnoredIssues]]. Same stop policy as [[fixLoop]] — `maxIterations`
    * counts FIX attempts, so up to `maxIterations + 1` evaluations run — but
    * additionally threads the immutable [[ReviewLoopState]] (reviewer history +
    * sessions) through each round so the cross-iteration data flow stays
    * explicit.
    */
  def run(): IgnoredIssues =
    // A progress marker, not a committing stage: the enclosing implement-task
    // stage already names the work and owns the commit (ADR 0018 §2.2).
    orca.display("Review & fix")
    // Two-phase selection: run the selector's gated effects (e.g. the
    // agentDriven picker LLM call) ONCE here, at loop start, inside this stage.
    // `selectRound` is the resulting pure per-iteration narrowing — passed down
    // to `evaluate` so it stays a function of its inputs.
    val selectRound: List[ReviewBatch] => List[Agent[?]] =
      reviewerSelection.prepare(reviewers, taskTitle, changedFiles)
    @scala.annotation.tailrec
    def loop(
        accumulated: IgnoredIssues,
        iteration: Int,
        state: ReviewLoopState
    ): IgnoredIssues =
      orca.display(s"Iteration ${iteration + 1}")
      val (result, nextState) = evaluate(state, selectRound)
      val issues = result.issues
      if issues.isEmpty then
        emitStep("No review comments")
        accumulated
      else if iteration >= maxIterations then
        emitStep(s"Reached max iterations ($maxIterations); bailing out")
        accumulated ++ IgnoredIssues(
          issues.map(i =>
            IgnoredIssue(i.title, s"max iterations ($maxIterations) reached")
          )
        )
      else
        val outcome = fix(issues)
        emitStep(
          s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}"
        )
        if outcome.fixed.isEmpty then
          accumulated ++ IgnoredIssues(outcome.ignored)
        else
          loop(
            accumulated ++ IgnoredIssues(outcome.ignored),
            iteration + 1,
            nextState
          )
    loop(IgnoredIssues(Nil), 0, ReviewLoopState.empty)

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

/** Run `command` via `bash -c`, capture both stdout and stderr, write the
  * combined output to a temp file, and ask `agent` to read that file and
  * summarise it as a `ReviewResult`. An empty output short-circuits to
  * `ReviewResult.empty` so clean runs skip the round-trip to the LLM. Override
  * `instructions` when the lint produces unusual shapes the default phrasing
  * doesn't fit.
  *
  * The output goes to a file rather than into the prompt because a lint command
  * can emit an unbounded amount of text (a full test or build run is hundreds
  * of KB), which would otherwise overflow the model's context window. The agent
  * reads the file with its read-only tools — in chunks if it's large. The
  * command's exit status is passed alongside: a zero status usually means
  * there's nothing to report, so the agent can skip reading the file entirely.
  * The temp file is removed once the summary returns.
  *
  * The LLM is invoked read-only: the task is file-in / JSON-out, and the agent
  * may verify a lint claim against the sources it references but should never
  * edit during the summarisation step.
  */
def lint(
    command: String,
    agent: Agent[?],
    instructions: String = ReviewLoopPrompts.SummariseLint
)(using FlowContext, InStage): ReviewResult =
  val proc = os
    .proc("bash", "-c", command)
    .call(check = false, mergeErrIntoOut = true)
  val output = proc.out.text().trim
  if output.isEmpty then ReviewResult.empty
  else
    // `finally` below removes it, so skip the JVM-exit hook (one per lint call
    // would otherwise accumulate over a long run).
    val outputFile =
      os.temp(
        output,
        prefix = "orca-lint-",
        suffix = ".log",
        deleteOnExit = false
      )
    try
      agent.withReadOnly
        .resultAs[ReviewResult]
        .autonomous
        .run(
          s"""$instructions
             |
             |`$command` exited with status ${proc.exitCode}. A zero status
             |usually means it succeeded with nothing to report — return an
             |empty result without reading the file in that case.
             |
             |Otherwise its entire combined stdout+stderr is in `$outputFile`
             |(it may be large — read it in parts if needed).""".stripMargin,
          emitPrompt = false
        )
        ._2
    finally
      val _ = os.remove(outputFile)
