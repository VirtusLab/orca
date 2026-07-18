package orca.review

// Compiled under capture checking (imports below) so CheckedPar's fan-out
// enforcement (ADR 0018 §6) fires at its call site. Tapir `derives`/macro types
// don't type-check under CC — keep them in a sibling non-CC file (see
// FixRequest.scala).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{
  CheckedPar,
  Configured,
  FlowContext,
  FlowControl,
  FlowSession,
  InStage,
  WorkspaceWrite
}
import orca.plan.Title

import orca.agents.{BackendTag, Agent, Chat}
import orca.events.OrcaEvent

import orca.util.TextUtil

/** The decision the fix-loop stop policy ([[stopPolicy]]) reaches for one
  * round, given that round's evaluated issues — before `fix` is (maybe) called.
  */
private[review] enum LoopStep:
  /** No issues found this round — the loop is done; nothing to accumulate. */
  case Done

  /** `maxIterations` fix attempts have already run. `ignored` folds the
    * still-open issues in with a "max iterations reached" reason.
    */
  case CapReached(ignored: IgnoredIssues)

  /** Issues remain and the cap hasn't been hit — hand them to `fix`. */
  case NeedsFix

/** The fix-loop stop policy, shared by [[fixLoop]] and [[ReviewFixLoop.run]]:
  * done when `issues` is empty; fold `issues` into [[IgnoredIssues]] (reason
  * `"max iterations (N) reached"`) once `iteration >= maxIterations`; otherwise
  * signal that `fix` should run.
  *
  * `maxIterations` counts FIX attempts, not evaluations, so a loop built on
  * this policy performs up to `maxIterations + 1` evaluations.
  */
private[review] def stopPolicy(
    issues: List[ReviewIssue],
    iteration: Int,
    maxIterations: Int
): LoopStep =
  if issues.isEmpty then LoopStep.Done
  else if iteration >= maxIterations then
    LoopStep.CapReached(
      IgnoredIssues(
        issues.map(i =>
          IgnoredIssue(i.title, s"max iterations ($maxIterations) reached")
        )
      )
    )
  else LoopStep.NeedsFix

/** Evaluate, fix, re-evaluate until the reviewer reports no issues, the fixer
  * reports zero fixes, or `maxIterations` fix attempts have been made. Issues
  * remaining when the cap is hit are folded into the returned `IgnoredIssues`
  * with a `max iterations reached` reason. See [[stopPolicy]] for the decision.
  *
  * The state-free entry point: no cross-iteration data to thread, so it
  * recurses directly. [[ReviewFixLoop.run]] (backing [[reviewAndFixLoop]])
  * additionally threads a [[ReviewLoopState]].
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => FixOutcome,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =
  @scala.annotation.tailrec
  def loop(accumulated: IgnoredIssues, iteration: Int): IgnoredIssues =
    // A progress marker, not a committing stage: runs under the caller's task
    // stage (ADR 0018 §2.2).
    orca.display(s"Iteration ${iteration + 1}")
    val issues = evaluate().issues
    stopPolicy(issues, iteration, maxIterations) match
      case LoopStep.Done =>
        orca.display("No review comments")
        accumulated
      case LoopStep.CapReached(ignored) =>
        orca.display(s"Reached max iterations ($maxIterations); bailing out")
        accumulated ++ ignored
      case LoopStep.NeedsFix =>
        val outcome = fix(issues)
        orca.display(
          s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}"
        )
        if outcome.fixed.isEmpty then
          accumulated ++ IgnoredIssues(outcome.ignored)
        else loop(accumulated ++ IgnoredIssues(outcome.ignored), iteration + 1)

  loop(IgnoredIssues(Nil), 0)

/** An opaque handle to one reviewer in `reviewAndFixLoop`'s configured roster.
  *
  * The `private[review]` constructor means a [[ReviewerSelector]] can only
  * return a subset/permutation of the entries it was handed — a foreign
  * reviewer is unrepresentable, so the loop needs no runtime roster-membership
  * defences.
  *
  * Identity is reference identity: the loop keys its per-reviewer session map
  * on the entry instance, and each roster agent is wrapped exactly once
  * ([[RosterEntry.wrap]]). Must stay a plain class, not a case class:
  * `runReviewersAndLint`'s session lookup uses `eq` while `evaluate`'s
  * `.distinct` uses `equals`, so a generated structural `equals` would collapse
  * distinct entries wrapping the same agent and split the two identity notions.
  */
final class RosterEntry[B <: BackendTag] private[review] (
    private[review] val agent: Agent[B]
):
  /** The reviewer's bare slug — its identity, and what the picker LLM is shown
    * and asked to echo. The `reviewer` cost-attribution role tag is applied
    * only later, at the loop's emission edge.
    */
  def name: String = agent.name

private[review] object RosterEntry:
  /** Wrap a roster agent, binding its backend tag so the entry's `agent` and
    * any session paired with it in [[ReviewLoopState.sessions]] share one `B`
    * by construction.
    */
  def wrap(a: Agent[?]): RosterEntry[?] =
    a match
      case a: Agent[b] => new RosterEntry[b](a)

/** One round of reviews, with each reviewer's individual outcome preserved and
  * kept in configured order, so the loop can decide which reviewers to re-run
  * next iteration based on which ones found issues.
  */
case class ReviewBatch(outcomes: List[(RosterEntry[?], ReviewResult)]):
  def reviewersWithIssues: List[RosterEntry[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
  def allIssues: List[ReviewIssue] =
    outcomes.flatMap(_._2.issues)

/** One reviewer's live [[Chat]], paired with its entry under a single backend
  * tag `B`. The chat bundles the role-tagged agent with its conversation id, so
  * a resume just calls the chat again.
  */
private case class SessionEntry[B <: BackendTag](
    entry: RosterEntry[B],
    chat: Chat[B]
)

/** All cross-iteration state for `reviewAndFixLoop`, in one immutable record.
  * `history` is consulted by [[ReviewerSelector]]; `sessions` holds one
  * [[SessionEntry]] per reviewer that has run at least once, looked up by entry
  * identity (`eq`).
  */
private case class ReviewLoopState(
    history: List[ReviewBatch],
    sessions: List[SessionEntry[?]]
)
private object ReviewLoopState:
  val empty: ReviewLoopState = ReviewLoopState(Nil, Nil)

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * any issues above `confidenceThreshold` to the coder through `coderSession`'s
  * seeded, structured door, and loop. `reviewerSelection` decides which
  * reviewers run each iteration; the default ([[ReviewerSelector.agentDriven]])
  * runs a picker LLM on the review-role agent's cheap tier. Pass
  * `ReviewerSelector.allEveryRound` to skip selection, or
  * `ReviewerSelector.agentDriven(...)` to point the picker at a specific model.
  *
  * The default picker resolves `reviewAgent`'s cheap variant; a backend with no
  * separate cheap tier (`.cheap` returns `this`, e.g. pi) simply runs the
  * picker on the full review-role agent — correct, just not cheaper.
  *
  * `coderSession` is the coder's durable [[FlowSession]] (obtain it once with
  * `agent.session(name, seed)`). Each fix turn goes through
  * [[FlowSession.resultAs]]`.autonomous.run`, so a coder whose backend
  * conversation is fresh or lost-on-resume is re-primed with the recorded seed
  * and progress preamble, and its learned wire id is persisted.
  *
  * The fix step instructs the agent to report a `FixOutcome`: titles of issues
  * actually fixed under `fixed`, anything not addressed under `ignored` with a
  * reason. The loop only re-evaluates when something was fixed — an empty
  * `fixed` means nothing new for the reviewers to find, so the loop halts.
  */
def reviewAndFixLoop[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    task: String,
    /** Which reviewers run each iteration. Default runs a picker LLM on the
      * review-role agent's cheap tier; [[ReviewerSelector.allEveryRound]] skips
      * selection, [[ReviewerSelector.agentDriven]]`(...)` picks a specific
      * model.
      */
    reviewerSelection: ReviewerSelector = ReviewerSelector.agentDriven,
    /** Shell commands run in order before each review round so reviewers and
      * the lint see formatted code and the committed tree stays formatted. Each
      * runs via `bash -c` in `ctx.workDir`, exit status ignored. The default
      * resolves the project's `ctx.stackSettings.format` (ADR 0019);
      * `Configured.Off` skips formatting, `Configured.Use(...)` overrides the
      * settings.
      */
    formatCommands: Configured[List[String]] = Configured.FromSettings,
    /** Commands + summariser agent for the lint gate run alongside the
      * reviewers each round (see [[Lint]]). The default builds the gate from
      * the project's `ctx.stackSettings.lint` with `reviewAgent.cheap` as the
      * summariser; empty settings build no gate. `Configured.Off` skips
      * linting, `Configured.Use(Lint(...))` overrides the settings.
      */
    lint: Configured[Lint] = Configured.FromSettings,
    confidenceThreshold: Double = 0.7,
    maxIterations: Int = 10,
    fixInstructions: String = ReviewLoopPrompts.Fix,
    /** Override the diff handed to each reviewer in its initial prompt.
      * Defaults to `ctx.git.diff()` re-sampled at the start of every iteration,
      * so a reviewer joining the active set on iteration N sees the working
      * tree including earlier fixes. Reviewers with an existing session resume
      * it and don't get the diff again. Pass `Some(...)` to pin the diff
      * (tests, or when the change set is already committed and `git.diff()`
      * would be empty).
      */
    initialDiff: Option[String] = None
)(using
    ctx: FlowContext,
    ev: InStage,
    fc: FlowControl,
    ws: WorkspaceWrite
): IgnoredIssues =
  // `Configured` resolution happens here at loop entry (ADR 0019): the config
  // then carries plain data, so the capture-checked fan-out below never reads
  // `ctx.stackSettings` (or touches `ctx.reviewAgent`) from a fork.
  val resolvedFormat: List[String] = formatCommands match
    case Configured.FromSettings => ctx.stackSettings.format
    case Configured.Off          => Nil
    case Configured.Use(cs)      => cs
  // Empty settings ≡ no gate: no `Lint` value is built (and `ctx.reviewAgent`
  // is not resolved), exactly like `Off`.
  val resolvedLint: Option[Lint] = lint match
    case Configured.FromSettings =>
      Option.when(ctx.stackSettings.lint.nonEmpty)(
        Lint(ctx.stackSettings.lint, ctx.reviewAgent.cheap)
      )
    case Configured.Off    => None
    case Configured.Use(l) => Some(l)
  // `ctx` (pure [[FlowContext]]) is what the fan-out closures may capture;
  // `fc`/`ws` (exclusive capabilities) are handed only to `run()`, so the durable
  // fix turn reaches the [[FlowSession]] door without those tokens landing in the
  // fan-out (ADR 0018 §6). Passed explicitly, not by implicit search: the
  // more-specific `fc: FlowControl` would otherwise be picked for the
  // constructor's `FlowContext` and its root capability rejected.
  new ReviewFixLoop(
    ReviewLoopConfig(
      coderSession = coderSession,
      reviewers = reviewers,
      reviewerSelection = reviewerSelection,
      task = task,
      formatCommands = resolvedFormat,
      lint = resolvedLint,
      confidenceThreshold = confidenceThreshold,
      maxIterations = maxIterations,
      fixInstructions = fixInstructions,
      initialDiff = initialDiff
    )
  )(using ctx, ev).run()(using fc, ws)

/** [[reviewAndFixLoop]]'s parameters bundled into one value so
  * [[ReviewFixLoop]]'s constructor doesn't mirror them field-for-field. See
  * `reviewAndFixLoop`'s parameter docs for each field.
  *
  * `fc`/`ws` deliberately stay out: they're exclusive capabilities threaded to
  * [[ReviewFixLoop.run]] as method parameters, so they never land in the
  * reviewer fan-out closures that capture the config-holding instance (ADR 0018
  * §6).
  *
  * `formatCommands`/`lint` hold RESOLVED values — `Configured` resolution
  * happens once at loop entry, so the fan-out only ever sees plain data.
  */
private[review] case class ReviewLoopConfig[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    reviewerSelection: ReviewerSelector,
    task: String,
    formatCommands: List[String],
    lint: Option[Lint],
    confidenceThreshold: Double,
    maxIterations: Int,
    fixInstructions: String,
    initialDiff: Option[String]
)

/** Implementation of [[reviewAndFixLoop]]: one instance per invocation holds
  * the loop-constant [[ReviewLoopConfig]] (fields imported below), so the
  * per-iteration logic reads as plain methods. Construct and call [[run]].
  *
  * All cross-iteration state lives in one immutable [[ReviewLoopState]]
  * threaded explicitly through [[run]] (no captured `var`): reviewers fan out
  * within an iteration but each fork reads the snapshot it was handed and the
  * next state is computed once after they all return — no concurrent mutation.
  */
private[review] class ReviewFixLoop[B <: BackendTag](
    config: ReviewLoopConfig[B]
)(using
    ctx: FlowContext,
    ev: InStage
):
  // `lint` excluded from the wildcard: the config field would otherwise shadow
  // the package-level `lint(command, agent)` summariser function this class calls
  // below — `config.lint` stays the qualified way to reach the field.
  import config.{lint as _, *}

  // The roster, wrapped once as identity-keyed handles (see [[RosterEntry]]).
  private val roster: List[RosterEntry[?]] = reviewers.map(RosterEntry.wrap)

  // A constant override skips the git call; the default samples the diff fresh
  // each iteration so a newly-active reviewer sees the latest, not the
  // loop-start one.
  private def sampleDiff(): String = initialDiff.getOrElse(ctx.git.diff())

  // Loop-constant context handed to the selector on every iteration: the task's
  // title plus the file paths from the diff at loop entry. Sampled here so each
  // iteration's selector call doesn't re-shell-out.
  private val taskTitle: Title = Title(task)
  private val changedFiles: List[String] =
    ReviewLoop.extractChangedFiles(sampleDiff())

  private def filterByConfidence(result: ReviewResult): ReviewResult =
    ReviewResult(issues =
      result.issues.filter(_.confidence >= confidenceThreshold)
    )

  /** Run one reviewer against an immutable sessions snapshot. Returns the
    * review result plus, on a reviewer's first call, the new [[SessionEntry]]
    * the caller folds into the next state. Pure with respect to its inputs — no
    * shared-state side effects — so the caller can run many in parallel.
    *
    * `stored` is the reviewer's existing [[SessionEntry]] (found by entry
    * identity), if any; `currentDiff` is consumed only on the first call.
    */
  private def reviewWithSession(
      e: RosterEntry[?],
      stored: Option[SessionEntry[?]],
      currentDiff: String
  ): (ReviewResult, Option[SessionEntry[?]]) =
    stored match
      case Some(se) => (resumeReview(se), None)
      case None     => firstReview(e, currentDiff)

  /** Resume a reviewer's existing session. The run carries the `reviewer` cost
    * role ([[ReviewerPrompts.Role]]) so the `TokensUsed` breakdown can subtotal
    * reviewer spend, without renaming the entry's identity.
    */
  private def resumeReview[B <: BackendTag](se: SessionEntry[B]): ReviewResult =
    se.chat
      .resultAs[ReviewResult]
      .autonomous
      .run(ReviewLoopPrompts.ReReview, emitPrompt = false)

  /** A reviewer's first call: mint a fresh [[Chat]] on the role-tagged agent
    * and pair it back with the entry so a later resume recovers it typed.
    * `currentDiff` seeds the initial framing.
    */
  private def firstReview[B <: BackendTag](
      e: RosterEntry[B],
      currentDiff: String
  ): (ReviewResult, Option[SessionEntry[?]]) =
    val chat = e.agent.withRole(ReviewerPrompts.Role).chat()
    val result =
      chat
        .resultAs[ReviewResult]
        .autonomous
        .run(
          ReviewLoopPrompts.initialReview(task, currentDiff),
          emitPrompt = false
        )
    (result, Some(SessionEntry(e, chat)))

  /** One parallel agent's contribution. The `Reviewer` variant carries the
    * roster entry that ran and any new [[SessionEntry]] that needs folding into
    * the loop state; `Lint` carries only its filtered result (no LLM session).
    */
  private enum AgentOutcome:
    case Reviewer(
        entry: RosterEntry[?],
        result: ReviewResult,
        newSession: Option[SessionEntry[?]]
    )
    case Lint(result: ReviewResult)

  /** Run every active reviewer plus the optional lint summariser concurrently,
    * emitting one Step per agent as it finishes. State is a parameter, not a
    * closure capture, so the parallel block never reads a moving var.
    * LLM-internal events emit from fork threads; [[OrcaListener]]
    * implementations must be thread-safe.
    *
    * The diff is sampled once per call so all first-time reviewers see the same
    * payload.
    */
  private def runReviewersAndLint(
      active: List[RosterEntry[?]],
      currentState: ReviewLoopState
  ): (
      List[(RosterEntry[?], ReviewResult)],
      Option[ReviewResult],
      ReviewLoopState
  ) =
    def storedFor(e: RosterEntry[?]): Option[SessionEntry[?]] =
      currentState.sessions.find(_.entry eq e)
    val needsDiff = active.exists(e => storedFor(e).isEmpty)
    val currentDiff = if needsDiff then sampleDiff() else ""

    val reviewerTasks: List[() => AgentOutcome] = active.map: e =>
      val stored = storedFor(e)
      () =>
        val (result, newSession) = reviewWithSession(e, stored, currentDiff)
        AgentOutcome.Reviewer(e, filterByConfidence(result), newSession)

    val lintTaskOpt: Option[() => AgentOutcome] =
      config.lint.map: l =>
        () =>
          // Group lint tokens under the same `reviewer` cost role as the
          // reviewers, under the bare identity "lint"; the tagged copy stays
          // local to this call.
          val labelled =
            l.agent.withName("lint").withRole(ReviewerPrompts.Role)
          AgentOutcome.Lint(filterByConfidence(lint(l.commands, labelled)))

    // The explicit type application is CC-forced: it widens both lists' element
    // type to `() => AgentOutcome` so their capture sets unify into the single
    // `C^` CheckedPar.mapParUnordered binds below. Deleting it breaks the CC
    // compile.
    val tasks = reviewerTasks.++[() => AgentOutcome](lintTaskOpt.toList)
    if tasks.isEmpty then (Nil, None, currentState)
    else
      val outcomes: List[AgentOutcome] =
        // Fan out through the capture-checked funnel (CheckedPar), so separation
        // checking guards the fork boundary: the shared `InStage` these thunks
        // capture is admitted (load-bearing — each reviewer reaches a gated LLM
        // `run`), while an exclusive `WorkspaceWrite`/`FlowControl` capture would
        // be a compile error here (ADR 0018 §6). Needs this file's two language
        // imports.
        CheckedPar.mapParUnordered(tasks.size)(tasks):
          // Display the bare slug — the `reviewer` role tag is a cost-report
          // grouping detail, not part of what the user sees.
          case AgentOutcome.Reviewer(e, res, _) =>
            ctx.emit(OrcaEvent.Step(formatReviewerOutcome(e.name, res)))
          case AgentOutcome.Lint(res) =>
            ctx.emit(OrcaEvent.Step(formatReviewerOutcome("lint", res)))

      val reviewerOutcomes = outcomes.collect:
        case AgentOutcome.Reviewer(e, res, _) => (e, res)
      val lintOutcome = outcomes.collectFirst:
        case AgentOutcome.Lint(res) => res
      val newSessions = outcomes.foldLeft(currentState.sessions):
        case (acc, AgentOutcome.Reviewer(_, _, Some(entry))) => entry :: acc
        case (acc, _)                                        => acc
      val nextState = ReviewLoopState(
        history = ReviewBatch(reviewerOutcomes) :: currentState.history,
        sessions = newSessions
      )
      (reviewerOutcomes, lintOutcome, nextState)

  private def evaluate(
      state: ReviewLoopState,
      selectRound: List[ReviewBatch] -> List[RosterEntry[?]]
  ): (ReviewResult, ReviewLoopState) =
    // Format before reviewing so the implementation's and each fix's edits are
    // cleaned up before reviewers and the lint see them, and the committed tree
    // stays formatted. Exit status ignored — a formatter failure shouldn't abort
    // the review. `mergeErrIntoOut` folds stderr into captured stdout so neither
    // stream reaches the terminal and tears the status row.
    formatCommands.foreach: cmd =>
      val _ = os
        .proc("bash", "-c", cmd)
        .call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)
    // The selector returns roster entries only, so no membership defence is
    // needed — just collapse an accidental duplicate (`.distinct` on entry
    // identity) so a reviewer runs at most once per round. An empty selection
    // stays empty: no reviewers run, the round finds no issues, and the stop
    // policy converges — the loop never resurrects the roster behind the
    // selector's back.
    val active = selectRound(state.history).distinct
    val totalAgents = active.size + (if config.lint.isDefined then 1 else 0)
    if totalAgents > 0 then
      ctx.emit(
        OrcaEvent.Step(
          s"Running ${TextUtil.pluralize(totalAgents, "review agent")}"
        )
      )
    // The confidence filter is applied before display so what's shown matches
    // what the fixer receives — otherwise low-confidence issues would be listed
    // per-reviewer but silently dropped from the fix payload.
    val (results, lintResult, nextState) = runReviewersAndLint(active, state)
    val result = ReviewResult(issues =
      results.flatMap(_._2.issues) ++ lintResult.toList.flatMap(_.issues)
    )
    (result, nextState)

  // Routed through the durable [[FlowSession]] door: a coder whose backend
  // conversation is fresh or lost gets the seed + progress preamble re-applied
  // and its learned wire id persisted. Runs on the collecting thread (outside the
  // reviewer fan-out), so its FlowControl/WorkspaceWrite tokens stay
  // method-scoped and never land in the `CheckedPar` closures (ADR 0018 §6).
  private def fix(issues: List[ReviewIssue])(using
      fc: FlowControl,
      ws: WorkspaceWrite
  ): FixOutcome =
    coderSession
      .resultAs[FixOutcome]
      .run(FixRequest(fixInstructions, issues), emitPrompt = false)

  /** Run the evaluate/fix loop to convergence and return the accumulated
    * [[IgnoredIssues]], applying the shared [[stopPolicy]] each round and
    * threading the immutable [[ReviewLoopState]] (reviewer history + sessions)
    * through each round.
    *
    * Takes [[FlowControl]] + [[WorkspaceWrite]] as method parameters (not
    * constructor fields) so the durable fix turn ([[fix]]) can reach the
    * [[FlowSession]] door while these exclusive capabilities stay out of the
    * instance — and therefore out of the reviewer fan-out closures, which
    * capture `this` (ADR 0018 §6).
    */
  def run()(using fc: FlowControl, ws: WorkspaceWrite): IgnoredIssues =
    // A progress marker, not a committing stage: the enclosing implement-task
    // stage already names the work and owns the commit (ADR 0018 §2.2).
    orca.display("Review & fix")
    // Two-phase selection: run the selector's gated effects (e.g. the agentDriven
    // picker LLM call) ONCE here, at loop start, inside this stage. `selectRound`
    // is the resulting pure per-iteration narrowing, passed to `evaluate` so it
    // stays a function of its inputs. Pass `ctx`/`ev` explicitly: the
    // more-specific `fc: FlowControl` in scope would otherwise be picked for
    // `prepare`'s `using FlowContext` and its root capability rejected.
    val selectRound: List[ReviewBatch] -> List[RosterEntry[?]] =
      reviewerSelection.prepare(roster, taskTitle, changedFiles)(using ctx, ev)
    @scala.annotation.tailrec
    def loop(
        accumulated: IgnoredIssues,
        iteration: Int,
        state: ReviewLoopState
    ): IgnoredIssues =
      orca.display(s"Iteration ${iteration + 1}")
      val (result, nextState) = evaluate(state, selectRound)
      val issues = result.issues
      stopPolicy(issues, iteration, maxIterations) match
        case LoopStep.Done =>
          orca.display("No review comments")
          accumulated
        case LoopStep.CapReached(ignored) =>
          orca.display(s"Reached max iterations ($maxIterations); bailing out")
          accumulated ++ ignored
        case LoopStep.NeedsFix =>
          val outcome = fix(issues)
          orca.display(
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
