package orca.review

// This file compiles under capture checking (the two language imports below)
// so that the CheckedPar fan-out enforcement (ADR 0018 §6) actually fires at
// its call site further down. Keep tapir `derives`/macro-expanding types out
// of here — they don't type-check under CC — and put them in a sibling
// non-CC file instead, as FixRequest.scala already does.
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{
  CheckedPar,
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
  * Shared by [[fixLoop]]'s state-free loop and [[ReviewFixLoop.run]]'s
  * state-threading loop, which otherwise differ only in what they thread across
  * iterations.
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
  * stop with nothing accumulated when `issues` is empty; stop and fold `issues`
  * into [[IgnoredIssues]] (reason: `"max iterations (N) reached"`) once
  * `iteration >= maxIterations`; otherwise signal that `fix` should run.
  *
  * `maxIterations` counts FIX attempts, not evaluations: the cap check only
  * fires once `iteration >= maxIterations`, so a loop built on this policy
  * performs up to `maxIterations + 1` evaluations (the extra one is the final
  * round that discovers the cap was reached).
  *
  * The halt-on-zero-fixed half of the policy — stop once `fix` reports nothing
  * fixed, since re-evaluating would just rediscover the same issues — is a
  * single `outcome.fixed.isEmpty` check with no cap-style arithmetic to share,
  * so both loops inline it directly after their `fix` call.
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
  * reports zero fixes (so re-evaluating would just rediscover the same things),
  * or `maxIterations` fix attempts have been made. Issues that remain when the
  * cap is hit are folded into the returned `IgnoredIssues` with a `max
  * iterations reached` reason so callers can surface them. See [[stopPolicy]]
  * for the shared decision.
  *
  * Each round emits an `Iteration N` progress marker (a `display`, not a
  * committing stage — it runs under the caller's task stage, ADR 0018 §2.2).
  *
  * This is the state-free entry point: it has no cross-iteration data to
  * thread, so it recurses directly. [[ReviewFixLoop.run]], which backs
  * [[reviewAndFixLoop]], additionally threads a [[ReviewLoopState]].
  */
def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => FixOutcome,
    maxIterations: Int = 10
)(using ctx: FlowContext): IgnoredIssues =
  @scala.annotation.tailrec
  def loop(accumulated: IgnoredIssues, iteration: Int): IgnoredIssues =
    // A progress marker, not a committing stage: this runs under the caller's
    // task stage (ADR 0018 §2.2), so it must not open its own stage.
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
  * A [[ReviewerSelector]] is handed the roster as `RosterEntry` values and can
  * only return a subset/permutation of them: the constructor is
  * `private[review]`, so a selector cannot fabricate an entry for an agent the
  * roster never contained. This makes "a foreign reviewer" unrepresentable at
  * the type level, so the loop needs no runtime roster-membership defences (no
  * foreign-drop warning, no silent full-roster fallback).
  *
  * Identity is reference identity (the default for a plain class): the loop
  * keys its per-reviewer session map on the entry instance, and each roster
  * agent is wrapped exactly once ([[RosterEntry.wrap]]), so `eq` on entries is
  * the reviewer's identity.
  *
  * Two call sites depend on this being a plain class, not a case class:
  * `runReviewersAndLint`'s session lookup (`currentState.sessions.find(_.entry
  * eq e)`) and `evaluate`'s `selectRound(state.history).distinct`, which
  * de-duplicates a selector's accidental repeats. A case class would generate
  * structural `equals`/`hashCode`, and `List.distinct` uses `equals`, not `eq`
  * — so `.distinct` would start collapsing by structural equality (every
  * `RosterEntry[B]` wrapping the same `agent` looks alike) while the session
  * lookup kept using `eq`, splitting the two "same entry" notions apart.
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
  /** Wrap a roster agent, binding its existential backend tag so the entry's
    * `agent` and any session paired with it in [[ReviewLoopState.sessions]]
    * share one `B` by construction (no cast needed to recover it later).
    */
  def wrap(a: Agent[?]): RosterEntry[?] =
    a match
      case a: Agent[b] => new RosterEntry[b](a)

/** One round of reviews, with each reviewer's individual outcome preserved. The
  * list keeps the order callers configured (so positions match
  * `allReviewers(claude)` etc.), and lets the loop decide which reviewers to
  * re-run on the next iteration based on which ones found issues this time.
  */
case class ReviewBatch(outcomes: List[(RosterEntry[?], ReviewResult)]):
  def reviewersWithIssues: List[RosterEntry[?]] =
    outcomes.collect { case (r, rr) if rr.issues.nonEmpty => r }
  def allIssues: List[ReviewIssue] =
    outcomes.flatMap(_._2.issues)

/** One reviewer's live [[Chat]], paired with the entry it belongs to under a
  * single existential backend tag `B`. The chat already bundles the role-tagged
  * agent with its conversation id, so a resume just calls the chat again — no
  * `SessionId.Untyped`/`.as[RB]` round-trip.
  */
private case class SessionEntry[B <: BackendTag](
    entry: RosterEntry[B],
    chat: Chat[B]
)

/** All cross-iteration state for `reviewAndFixLoop`, in one immutable record.
  * `history` is consulted by [[ReviewerSelector]]; `sessions` holds one
  * [[SessionEntry]] per reviewer that has run at least once, looked up by entry
  * identity (`eq`). See [[reviewWithSession]] for how the existential pairing
  * keeps the recovered session id typed to the reviewer's backend.
  */
private case class ReviewLoopState(
    history: List[ReviewBatch],
    sessions: List[SessionEntry[?]]
)
private object ReviewLoopState:
  val empty: ReviewLoopState = ReviewLoopState(Nil, Nil)

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * any issues above `confidenceThreshold` to the coder — through
  * `coderSession`'s seeded, structured door — and loop. `reviewerSelection`
  * decides which reviewers run each iteration; the default
  * ([[ReviewerSelector.agentDriven]]) runs a picker LLM on the flow's lead
  * cheap tier. Pass `ReviewerSelector.allEveryRound` to skip selection, or
  * `ReviewerSelector.agentDriven(...)` to point the picker at a specific model.
  *
  * The default picker resolves the lead tool's cheap variant: four backends
  * have a distinct cheap tier (claude→haiku, codex→mini,
  * opencode→anthropicHaiku, gemini→flash); pi has no separate tier, so
  * `pi.cheap` is `pi` itself (pi selects the model via its own CLI config) and
  * the picker simply runs on the lead pi tool — correct, just not a cheaper
  * model. A hypothetical backend without a cheap tier behaves the same
  * (`.cheap` returns `this`).
  *
  * `coderSession` is the coder's durable [[FlowSession]] (obtain it once with
  * `agent.session(name, seed)` and pass the handle here). Each fix turn goes
  * through [[FlowSession.resultAs]]`.autonomous.run`, so a coder whose backend
  * conversation is fresh or lost-on-resume is re-primed with the recorded seed
  * and progress preamble, and its learned wire id is persisted — the durable
  * protocol the pre-2C raw-door fix turn silently skipped.
  *
  * The fix step instructs the agent to report a `FixOutcome`: list the titles
  * of issues actually fixed in code under `fixed`, and anything not addressed
  * (environmental, out-of-scope, false positive) under `ignored` with a reason.
  * The loop only re-evaluates when something was fixed — if `fixed` is empty
  * there's nothing new for the reviewers to find, so the loop halts.
  */
def reviewAndFixLoop[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    task: String,
    /** Which reviewers run each iteration. The default runs a picker LLM on the
      * flow's lead cheap tier; pass [[ReviewerSelector.allEveryRound]] to skip
      * selection, or [[ReviewerSelector.agentDriven]]`(...)` to point the
      * picker at a specific model.
      */
    reviewerSelection: ReviewerSelector = ReviewerSelector.agentDriven,
    /** Shell command run before each review round — after the implementation
      * and after every fix — so reviewers and the lint see formatted code and
      * the committed tree stays formatted. Run via `bash -c`, exit status
      * ignored (a formatter that fails shouldn't abort the review). E.g. `"sbt
      * scalafmtAll"`, `"cargo fmt"`, `"prettier -w ."`.
      */
    formatCommand: Option[String] = None,
    /** Command + summariser agent for the lint gate run alongside the reviewers
      * each round. `None` (the default) skips linting entirely. See [[Lint]]
      * for why the pair is bundled into one value.
      */
    lint: Option[Lint] = None,
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
)(using
    ctx: FlowContext,
    ev: InStage,
    fc: FlowControl,
    ws: WorkspaceWrite
): IgnoredIssues =
  // `ctx` (the pure [[FlowContext]]) is what the loop's fan-out closures may
  // capture; `fc`/`ws` (exclusive capabilities) are handed only to `run()`, so
  // the durable fix turn reaches the [[FlowSession]] door without those tokens
  // ever landing in the fan-out (ADR 0018 §6). Passed explicitly rather than by
  // implicit search: the more-specific `fc: FlowControl` would otherwise be
  // picked for the constructor's `FlowContext` and its root capability rejected.
  new ReviewFixLoop(
    ReviewLoopConfig(
      coderSession = coderSession,
      reviewers = reviewers,
      reviewerSelection = reviewerSelection,
      task = task,
      formatCommand = formatCommand,
      lint = lint,
      confidenceThreshold = confidenceThreshold,
      maxIterations = maxIterations,
      fixInstructions = fixInstructions,
      initialDiff = initialDiff
    )
  )(using ctx, ev).run()(using fc, ws)

/** [[reviewAndFixLoop]]'s 10 parameters, bundled into one value so
  * [[ReviewFixLoop]]'s constructor doesn't mirror them field-for-field (the
  * `FlowWiring`/`flow(...)` shape — `runner/.../FlowWiring.scala` — collects a
  * public function's named arguments into one internal record the same way).
  * `reviewAndFixLoop` itself keeps its own 10 named, documented parameters as
  * the public surface; this bundling is internal-only. See `reviewAndFixLoop`'s
  * parameter docs for the full description of each field.
  *
  * `fc`/`ws` deliberately stay out: they're exclusive capabilities threaded to
  * [[ReviewFixLoop.run]] as method parameters, not loop configuration, so they
  * never land in the reviewer fan-out closures that capture the config-holding
  * instance (ADR 0018 §6).
  */
private[review] case class ReviewLoopConfig[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    reviewerSelection: ReviewerSelector,
    task: String,
    formatCommand: Option[String],
    lint: Option[Lint],
    confidenceThreshold: Double,
    maxIterations: Int,
    fixInstructions: String,
    initialDiff: Option[String]
)

/** Implementation of [[reviewAndFixLoop]]: one instance per invocation holds
  * the loop-constant configuration ([[ReviewLoopConfig]]) plus its individual
  * fields (imported below) as if they were constructor params, so the
  * per-iteration logic reads top-down as plain methods rather than a stack of
  * nested closures. Construct and call [[run]].
  *
  * All cross-iteration state lives in one immutable [[ReviewLoopState]]
  * threaded explicitly through [[run]] (no captured `var`): within an iteration
  * the reviewers fan out via `Flow.mapParUnordered`, but each fork reads the
  * snapshot it was handed and the next state is computed once after they all
  * return — so no concurrent mutation, no `mutable.Map`, no
  * `ConcurrentHashMap`.
  *
  * See [[reviewAndFixLoop]]'s parameter docs for the full description of each
  * [[ReviewLoopConfig]] field.
  */
private[review] class ReviewFixLoop[B <: BackendTag](
    config: ReviewLoopConfig[B]
)(using
    ctx: FlowContext,
    ev: InStage
):
  // `lint` excluded from the wildcard: the config field of that name would
  // otherwise shadow the package-level `lint(command, agent)` summariser
  // function this class calls below — `config.lint` stays the qualified way
  // to reach the field.
  import config.{lint as _, *}

  // The roster, wrapped once as identity-keyed handles. A selector receives
  // these and may only return a subset/permutation of them (foreign agents are
  // unrepresentable), and the session map keys on these instances by `eq` — so
  // duplicate reviewer names cannot break session threading and no
  // name-uniqueness `require` is needed.
  private val roster: List[RosterEntry[?]] = reviewers.map(RosterEntry.wrap)

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
    * the review result plus, on a reviewer's first call, the new
    * [[SessionEntry]] the caller should fold into the next state. Pure with
    * respect to its inputs — no side effects on shared state — which lets the
    * caller run many of these in parallel.
    *
    * `stored` is the reviewer's existing [[SessionEntry]] (found by entry
    * identity), if any; `currentDiff` is the working-tree diff the caller
    * sampled at the start of this iteration, consumed only on the first call.
    *
    * No cast is involved: a resume runs `stored`'s own paired entry+chat
    * ([[resumeReview]]), whose backend tag `B` the wrapper carries by
    * construction; a first call binds the entry's `B` ([[firstReview]]) and
    * pairs the fresh `Chat[B]` back with it. The pairing is a compile-time
    * invariant, not a claim recovered at runtime.
    */
  private def reviewWithSession(
      e: RosterEntry[?],
      stored: Option[SessionEntry[?]],
      currentDiff: String
  ): (ReviewResult, Option[SessionEntry[?]]) =
    stored match
      case Some(se) => (resumeReview(se), None)
      case None     => firstReview(e, currentDiff)

  /** Resume a reviewer's existing session. `se` pairs the entry with a session
    * id under one `B`, so `se.session` is already typed to `se.entry.agent`'s
    * backend — no recovery cast. The LLM run is tagged with the `reviewer` cost
    * role ([[ReviewerPrompts.Role]]) so the `TokensUsed` breakdown can
    * group/subtotal reviewer spend, without renaming the entry's identity.
    */
  private def resumeReview[B <: BackendTag](se: SessionEntry[B]): ReviewResult =
    se.chat
      .resultAs[ReviewResult]
      .autonomous
      .run(ReviewLoopPrompts.ReReview, emitPrompt = false)

  /** A reviewer's first call: bind the entry's backend tag `B`, mint a fresh
    * [[Chat]] on the role-tagged agent, and pair it back with the entry so a
    * later resume recovers it typed. `currentDiff` seeds the initial framing.
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
          // dimension reviewers, under the bare identity "lint"; the
          // renamed/tagged copy stays local to this call.
          val labelled =
            l.agent.withName("lint").withRole(ReviewerPrompts.Role)
          AgentOutcome.Lint(filterByConfidence(lint(l.commands, labelled)))

    // The explicit type application is CC-forced: it widens both lists'
    // element type to the impure function type `() => AgentOutcome` so their
    // capture sets unify into the single `C^` CheckedPar.mapParUnordered
    // binds below. Deleting it breaks the CC compile.
    val tasks = reviewerTasks.++[() => AgentOutcome](lintTaskOpt.toList)
    if tasks.isEmpty then (Nil, None, currentState)
    else
      val outcomes: List[AgentOutcome] =
        // Fan out through the capture-checked funnel (CheckedPar) rather than
        // Ox's Flow directly, so separation checking guards the fork boundary:
        // the shared `InStage` these thunks capture is admitted (load-bearing —
        // each reviewer reaches a gated LLM `run`), while an exclusive
        // `WorkspaceWrite`/`FlowControl` capture would be a compile error here
        // (ADR 0018 §6). Enforcement needs this file's two language imports.
        CheckedPar.mapParUnordered(tasks.size)(tasks):
          // Display the bare slug — the `reviewer` role tag is a cost-report
          // grouping detail, not part of what the user sees per reviewer.
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
    // Format before reviewing so the implementation's (and each prior fix's)
    // edits are cleaned up before reviewers and the lint see them, and the
    // committed tree stays formatted. Exit status ignored — a formatter failure
    // shouldn't abort the review. `mergeErrIntoOut` folds stderr into the
    // captured stdout so neither stream reaches the terminal and tears the
    // status row (previously stdout was captured but stderr leaked through).
    formatCommand.foreach: cmd =>
      val _ =
        os.proc("bash", "-c", cmd).call(check = false, mergeErrIntoOut = true)
    // The selector returns roster entries only, so there is no membership
    // defence to apply — just collapse an accidental duplicate (`.distinct` on
    // entry identity) so a reviewer runs at most once per round. An empty
    // selection stays empty: no reviewers run, the round finds no issues, and
    // the shared stop policy converges — the loop never silently resurrects the
    // roster behind the selector's back.
    val active = selectRound(state.history).distinct
    val totalAgents = active.size + (if config.lint.isDefined then 1 else 0)
    if totalAgents > 0 then
      ctx.emit(
        OrcaEvent.Step(
          s"Running ${TextUtil.pluralize(totalAgents, "review agent")}"
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

  // Routed through the durable [[FlowSession]] door (not the raw structured
  // door it used pre-2C): a coder whose backend conversation is fresh or lost
  // gets the seed + progress preamble re-applied and its learned wire id
  // persisted. Runs on the collecting thread (outside the reviewer fan-out), so
  // the FlowControl/WorkspaceWrite tokens it needs stay method-scoped and are
  // never captured into the `CheckedPar` closures (ADR 0018 §6).
  private def fix(issues: List[ReviewIssue])(using
      fc: FlowControl,
      ws: WorkspaceWrite
  ): FixOutcome =
    coderSession
      .resultAs[FixOutcome]
      .run(FixRequest(fixInstructions, issues), emitPrompt = false)

  /** Run the evaluate/fix loop to convergence and return the accumulated
    * [[IgnoredIssues]], applying the shared [[stopPolicy]] each round — see
    * there for what it decides — but additionally threading the immutable
    * [[ReviewLoopState]] (reviewer history + sessions) through each round so
    * the cross-iteration data flow stays explicit.
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
    // Two-phase selection: run the selector's gated effects (e.g. the
    // agentDriven picker LLM call) ONCE here, at loop start, inside this stage.
    // `selectRound` is the resulting pure per-iteration narrowing — passed down
    // to `evaluate` so it stays a function of its inputs.
    // Pass `ctx`/`ev` explicitly: under capture checking the more-specific
    // `fc: FlowControl` in scope would otherwise be picked for `prepare`'s
    // `using FlowContext` and its root capability rejected (same reason the
    // `reviewAndFixLoop` constructor call passes them positionally).
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
