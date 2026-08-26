package orca.review

// Compiled under capture checking (imports below) so CheckedPar's fan-out
// enforcement fires at its call site: the reviewer fan-out may capture the
// shared `InStage`, and an exclusive `FlowControl`/`WorkspaceWrite` capture is
// a compile error (ADR 0018 §6, pinned by `orca.CcNegativeCompileTest`). That
// is why `fc`/`ws` are method parameters rather than fields throughout this
// file. Tapir `derives`/macro types don't type-check under CC — keep them in a
// sibling non-CC file (see FixRequest.scala).
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
import orca.plan.{Task, Title}

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

/** How many fix attempts [[fixLoop]] and [[reviewAndFixLoop]] allow before
  * giving up. Named once so the two can't drift apart. Public because the
  * shipped flows pass the number at their call sites instead of inheriting it,
  * and a test checks those numbers against this one.
  */
val DefaultMaxIterations: Int = 3

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

/** The headline shown when a loop gives up at the cap. */
private[review] def capExitMessage(maxIterations: Int): String =
  s"Reached max iterations ($maxIterations)"

/** The headline for a round that found nothing for the fixer. A run can reach
  * it with findings still open — declined earlier — so it must not claim the
  * review came back clean.
  */
private[review] def cleanExitMessage(open: IgnoredIssues): String =
  if open.issues.isEmpty then "No review comments" else "No issues to fix"

/** The headline shown when a loop stops because the fixer reported no fixes. */
private[review] val FixerHaltMessage: String =
  "Fixer reported no fixes; ending review"

/** The reason recorded against an issue the fixer left unaccounted for. Only
  * the halt exits record these, and both halt precisely because the fixer
  * reported no fixes.
  */
private[review] val NoFixesReason: String = "fixer reported no fixes"

/** The entries the fixer-reported-no-fixes halt adds, for the caller to fold
  * via [[recordIgnored]].
  */
private def fixerHaltAdditions(
    outcome: ReconciledFixOutcome
): List[IgnoredIssue] =
  outcome.ignored ++ outcome.unaccounted.map(IgnoredIssue(_, NoFixesReason))

/** Announce a loop exit: `headline`, then the closing block naming everything
  * `open` still holds and why ([[formatUnresolvedFindings]]).
  *
  * Every exit of both loops ends here, because the returned [[IgnoredIssues]]
  * is the one thing a run's callers discard — without this the record would
  * exist only in a value nobody reads. Intermediate rounds deliberately don't
  * print it: a finding declined in round one may well be fixed in round two.
  */
private def announceExit(
    headline: String,
    open: IgnoredIssues,
    severities: Map[Title, Severity]
)(using FlowContext): Unit =
  orca.display(headline)
  formatUnresolvedFindings(open.issues, severities).foreach(orca.display)

/** `severities` extended with the severity of each of `issues`, so a finding
  * recorded in one round can still be labelled at an exit rounds later.
  */
private def indexSeverities(
    severities: Map[Title, Severity],
    issues: List[ReviewIssue]
): Map[Title, Severity] =
  severities ++ issues.map(i => i.title -> i.severity)

/** `existing` with `additions` merged in, keyed by title: a title `existing`
  * already carries is refreshed with the latest reason in place, a new one is
  * appended, and duplicate titles within `additions` collapse to the last.
  *
  * Every accumulation point in both loops goes through this, so a finding
  * declined in round one and re-declined — or later reported unaccounted for —
  * comes back as one entry saying the last thing known about it, never as two
  * entries with contradictory reasons. A title is the loop's one notion of "the
  * same finding again", in the fixer's accumulated declines and in the
  * [[IgnoredIssues]] any exit returns.
  */
private[review] def recordIgnored(
    existing: IgnoredIssues,
    additions: List[IgnoredIssue]
): IgnoredIssues =
  val latest = additions.map(i => i.title -> i).toMap
  val existingTitles = existing.issues.map(_.title).toSet
  val refreshed = existing.issues.map(i => latest.getOrElse(i.title, i))
  val added =
    additions
      .map(_.title)
      .distinct
      .filterNot(existingTitles.contains)
      .map(latest)
  IgnoredIssues(refreshed ++ added)

/** `accumulated` with the fixer's `fixed` titles dropped, then `additions`
  * folded in ([[recordIgnored]]).
  *
  * Both loops prune here, on the continue path, because this is the one point a
  * fix verdict is observed: an entry left in past it reports a finding that was
  * fixed as still ignored, and — since the carried set is also what the next
  * round's reviewers are told was declined — tells them so too.
  *
  * The trade-off: a fixer that falsely claims a fix drops the entry from the
  * record unless a reviewer reports the finding again. That re-report is the
  * same recovery the continue path already relies on for `unaccounted` titles
  * ([[ReconciledFixOutcome]]).
  */
private[review] def carryPastFixes(
    accumulated: IgnoredIssues,
    fixed: Set[Title],
    additions: List[IgnoredIssue]
): IgnoredIssues =
  recordIgnored(
    IgnoredIssues(accumulated.issues.filterNot(i => fixed.contains(i.title))),
    additions
  )

/** Announce the fixer's replies that matched no issue it was handed — the
  * visible sign of a degraded fix turn, whose entries are otherwise dropped.
  */
private[review] def announceFixTurn(
    outcome: ReconciledFixOutcome
)(using ctx: FlowContext): Unit =
  if outcome.unresolvedEchoes.nonEmpty then
    orca.display(
      s"Fixer named ${outcome.unresolvedEchoes.mkString(", ")}, " +
        "which matched no issue it was handed"
    )
  // Both loops continue exactly when something was fixed, so only then does the
  // line promise another round; the halt branch prints FixerHaltMessage.
  val next =
    if outcome.fixed.nonEmpty then "; reviewing again after the fixes" else ""
  orca.display(
    s"Fixed ${outcome.fixed.size}, ignored ${outcome.ignored.size}$next"
  )

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
    maxIterations: Int = DefaultMaxIterations
)(using ctx: FlowContext): IgnoredIssues =
  @scala.annotation.tailrec
  def loop(
      accumulated: IgnoredIssues,
      iteration: Int,
      severities: Map[Title, Severity]
  ): IgnoredIssues =
    // A progress marker, not a committing stage (ADR 0018 §2.2).
    orca.display(s"Iteration ${iteration + 1}")
    val issues = evaluate().issues
    val seenSeverities = indexSeverities(severities, issues)
    stopPolicy(
      issues,
      iteration = iteration,
      maxIterations = maxIterations
    ) match
      case LoopStep.Done =>
        announceExit(cleanExitMessage(accumulated), accumulated, seenSeverities)
        accumulated
      case LoopStep.CapReached(ignored) =>
        val open = recordIgnored(accumulated, ignored.issues)
        announceExit(capExitMessage(maxIterations), open, seenSeverities)
        open
      case LoopStep.NeedsFix =>
        // One evaluator, so it is the round's only agent — index 0.
        val outcome =
          FixOutcome.reconcile(KeyedIssue.forAgent(0, issues), fix(issues))
        announceFixTurn(outcome)
        if outcome.fixed.isEmpty then
          val open = recordIgnored(accumulated, fixerHaltAdditions(outcome))
          announceExit(FixerHaltMessage, open, seenSeverities)
          open
        else
          loop(
            carryPastFixes(accumulated, outcome.fixed.toSet, outcome.ignored),
            iteration + 1,
            seenSeverities
          )

  loop(IgnoredIssues(Nil), 0, Map.empty)

/** One reviewer's live [[Chat]]. The chat bundles the role-tagged agent with
  * its conversation id, so a resume just calls the chat again.
  *
  * `lastSent` is the change set this reviewer was last sent, not the last one
  * sampled: a resume compares against it to decide whether there is anything
  * new to send ([[ReReviewChanges.of]]).
  */
private case class SessionEntry(chat: Chat[?], lastSent: LastSent)

/** All cross-iteration state for `reviewAndFixLoop`, in one immutable record.
  * `history` is consulted by [[ReviewerSelector]]; `sessions` holds one
  * [[SessionEntry]] per reviewer that has run at least once; `lintChat` is
  * whatever conversation the last [[lint]] call handed back as safe to resume.
  */
private case class ReviewLoopState(
    history: List[ReviewBatch],
    sessions: Map[ReviewerId, SessionEntry],
    lintChat: Option[Lint.Summariser]
):
  def afterRound(
      reviewers: List[RoundContribution],
      lintChat: Option[Lint.Summariser]
  ): ReviewLoopState =
    ReviewLoopState(
      history = ReviewBatch(
        reviewers.map(c => (c.entry, ReviewResult(c.issues.map(_.issue))))
      ) :: history,
      sessions =
        sessions ++ reviewers.flatMap(c => c.newSession.map(c.entry.id -> _)),
      lintChat = lintChat
    )

private object ReviewLoopState:
  val empty: ReviewLoopState = ReviewLoopState(
    history = Nil,
    sessions = Map.empty,
    lintChat = None
  )

/** What one reviewer contributed to a round: its findings, keyed as the fixer
  * will see them, and the [[SessionEntry]] the loop state has to fold in (a
  * fresh one on its first call, an advanced one after a resume that sent
  * something, `None` when there is nothing new to record).
  */
private case class RoundContribution(
    entry: RosterEntry,
    issues: List[KeyedIssue],
    newSession: Option[SessionEntry]
)

/** What the lint command gate ([[Lint]], run alongside the reviewers)
  * contributed to a round: its keyed findings and the conversation [[lint]]
  * handed back as safe to resume.
  */
private case class LintContribution(
    issues: List[KeyedIssue],
    resumableSummariser: Option[Lint.Summariser]
)

/** The lint gate this round, paired with the conversation its summary runs on,
  * so neither can go missing without the other.
  */
private case class LintRound(gate: Lint, summariser: Lint.Summariser)

/** One evaluation round's outcome: everything reported this round, keyed as the
  * fixer will see it, and the state to carry into the next round.
  */
private case class RoundOutcome(
    issues: List[KeyedIssue],
    state: ReviewLoopState
)

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * every issue they report to the coder through `coderSession`'s seeded,
  * structured door, and loop. `reviewerSelection` decides which reviewers run
  * each iteration; the default narrows to the reviewers that reported last
  * round, so a reviewer that goes quiet won't see the fixes made after it
  * stopped running (see [[ReviewerSelector]]).
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
  *
  * The `ignored` entries go to the next round's reviewers, so a reviewer knows
  * a finding was considered and refused rather than missed — the one thing in
  * the loop it could not have worked out by reading the code. The `fixed`
  * titles do not: a reviewer told its finding was fixed is handed the answer it
  * exists to work out for itself. A refusal the fixer later reverses drops out
  * of that set the round the fix is observed.
  *
  * Nothing still open is lost at any exit: whatever the fixer declined or left
  * unaccounted for comes back in the returned [[IgnoredIssues]] with a reason,
  * and is printed at the exit.
  */
def reviewAndFixLoop[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    /** The work under review. Reviewers are shown its title and its
      * description, alongside `userRequest`, each labelled — so a reviewer can
      * tell what the user asked for apart from what the planner decided, and
      * report a finding against the planned choice. A flow with no planning
      * stage passes its prompt as the title and an empty description.
      */
    task: Task,
    /** What the user asked for, shown to reviewers alongside the task. Defaults
      * to the run's prompt; a flow whose prompt is only a pointer — an issue
      * reference, say — passes the text it points at instead.
      */
    userRequest: Option[String] = None,
    /** Which reviewers run each iteration — see [[ReviewerSelector]] for the
      * shipped variants and how each trades coverage for tokens.
      */
    reviewerSelection: ReviewerSelector = ReviewerSelector.default,
    /** Shell commands run in order before each review round so reviewers and
      * the lint see formatted code and the committed tree stays formatted. Each
      * runs via `bash -c` in `ctx.workDir`; a nonzero exit is reported but
      * doesn't abort the round. The default resolves the project's
      * `ctx.stackSettings.format` (ADR 0019); `Configured.Off` skips
      * formatting, `Configured.Use(...)` overrides the settings.
      */
    formatCommands: Configured[List[String]] = Configured.FromSettings,
    /** Commands + summariser agent for the lint gate run alongside the
      * reviewers each round (see [[Lint]]). The default builds the gate from
      * the project's `ctx.stackSettings.lint` with `reviewAgent.cheap` as the
      * summariser; empty settings build no gate. `Configured.Off` skips
      * linting, `Configured.Use(Lint(...))` overrides the settings.
      */
    lint: Configured[Lint] = Configured.FromSettings,
    /** How many fix attempts before the loop gives up, folding whatever is
      * still open into the returned [[IgnoredIssues]]. Counts fixes, not
      * evaluations — see [[stopPolicy]].
      */
    maxIterations: Int = DefaultMaxIterations,
    fixInstructions: String = ReviewLoopPrompts.Fix,
    /** Where the change set under review comes from: sampled from the enclosing
      * stage each round, or pinned by the caller. Pinning changes what
      * reviewers and the selector are told, not just the diff text — see
      * [[ReviewDiff]].
      */
    diff: ReviewDiff = ReviewDiff.SampleFromStage
)(using
    ctx: FlowContext,
    ev: InStage,
    fc: FlowControl,
    ws: WorkspaceWrite
): IgnoredIssues =
  // `Configured` resolution happens here at loop entry (ADR 0019), so the
  // config carries plain data.
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
  // `fc` is read here, at loop entry, so what the loop carries is the plain
  // commit hash rather than the capability it came from.
  val diffSource: ReviewDiffSource = diff match
    case ReviewDiff.SampleFromStage =>
      ReviewDiffSource.Sampled(ctx.git, fc.stageBaseCommit)
    case ReviewDiff.Pinned(d) => ReviewDiffSource.Pinned(d)
  // `ctx`/`ev` passed explicitly, not by implicit search: the more-specific
  // `fc: FlowControl` would otherwise be picked for the constructor's
  // `FlowContext` and its root capability rejected.
  new ReviewFixLoop(
    ReviewLoopConfig(
      coderSession = coderSession,
      reviewers = reviewers,
      reviewerSelection = reviewerSelection,
      task = task,
      userRequest = userRequest.getOrElse(ctx.userPrompt),
      formatCommands = resolvedFormat,
      lintGate = resolvedLint,
      maxIterations = maxIterations,
      fixInstructions = fixInstructions,
      diffSource = diffSource
    )
  )(using ctx, ev).run()(using fc, ws)

/** [[reviewAndFixLoop]]'s parameters bundled into one value so
  * [[ReviewFixLoop]]'s constructor doesn't mirror them field-for-field. See
  * `reviewAndFixLoop`'s parameter docs for each field; `formatCommands` and
  * `lintGate` hold values `Configured` already resolved, and `userRequest`
  * holds the caller's override or, failing that, the run's `ctx.userPrompt`.
  */
private[review] case class ReviewLoopConfig[B <: BackendTag](
    coderSession: FlowSession[B],
    reviewers: List[Agent[?]],
    reviewerSelection: ReviewerSelector,
    task: Task,
    userRequest: String,
    formatCommands: List[String],
    lintGate: Option[Lint],
    maxIterations: Int,
    fixInstructions: String,
    diffSource: ReviewDiffSource
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
  import config.*

  private val roster: List[RosterEntry] = RosterEntry.roster(reviewers)

  // Displayed for the lint gate's own findings, and the identity its LLM runs
  // are tagged with.
  private val lintName: String = "lint"

  /** Run one reviewer against an immutable sessions snapshot. Returns the
    * review result plus the [[SessionEntry]] the caller folds into the next
    * state — a new one on the reviewer's first call, an updated one when a
    * resume advanced its `lastSent`, `None` when there is nothing to record.
    * Pure with respect to its inputs — no shared-state side effects — so the
    * caller can run many in parallel.
    *
    * `stored` is the reviewer's existing [[SessionEntry]], if any.
    */
  private def reviewWithSession(
      e: RosterEntry,
      stored: Option[SessionEntry],
      current: DiffSample,
      declined: List[IgnoredIssue]
  ): (ReviewResult, Option[SessionEntry]) =
    stored match
      case Some(se) => resumeReview(se, current, declined)
      case None     => firstReview(e, current.diff, declined)

  /** Resume a reviewer's existing session, sending what is new to it since its
    * last round: the change set ([[ReReviewChanges]]) and what the fixer
    * declined to fix. The run carries the `reviewer` cost role
    * ([[ReviewerPrompts.Role]]) so the `TokensUsed` breakdown can subtotal
    * reviewer spend, without renaming the entry's identity.
    *
    * The fixer's `fixed` titles are deliberately not sent — see
    * [[reviewAndFixLoop]].
    */
  private def resumeReview(
      se: SessionEntry,
      current: DiffSample,
      declined: List[IgnoredIssue]
  ): (ReviewResult, Option[SessionEntry]) =
    val changes = ReReviewChanges.of(se.lastSent, current)
    val result =
      se.chat
        .resultAs[ReviewResult]
        .autonomous
        .run(ReviewLoopPrompts.reReview(changes, declined), emitPrompt = false)
    // Nothing is sent on `AlreadySeen`, so the reviewer keeps comparing against
    // what it has seen. `PathsOnly` still records the whole diff, not the
    // paths: the next round compares diff text, so a change that rewrites those
    // files without adding or removing any must still register.
    val advanced = changes match
      case ReReviewChanges.Updated(_) =>
        Some(se.copy(lastSent = LastSent.inlined(current.diff)))
      case ReReviewChanges.TooLarge(_) =>
        Some(se.copy(lastSent = LastSent.PathsOnly(current.diff)))
      case ReReviewChanges.AlreadySeen(_) => None
    (result, advanced)

  /** A reviewer's first call: mint a fresh [[Chat]] on the role-tagged agent so
    * a later round can resume it. `currentDiff` seeds the initial framing, and
    * `declined` carries the fixer's refusals to a reviewer joining after round
    * one.
    */
  private def firstReview(
      e: RosterEntry,
      currentDiff: String,
      declined: List[IgnoredIssue]
  ): (ReviewResult, Option[SessionEntry]) =
    val chat = e.agent.withRole(ReviewerPrompts.Role).chat()
    val result =
      chat
        .resultAs[ReviewResult]
        .autonomous
        .run(
          ReviewLoopPrompts.initialReview(
            task = task,
            userRequest = userRequest,
            diff = currentDiff,
            diffIntro = diffSource.diffIntro,
            base = diffSource.base,
            declined = declined
          ),
          emitPrompt = false
        )
    (result, Some(SessionEntry(chat, LastSent.inlined(currentDiff))))

  /** What one fork of the round's fan-out came back with — the same
    * contribution the loop state folds in, tagged with which kind of agent
    * produced it.
    */
  private enum AgentOutcome:
    case Reviewer(contribution: RoundContribution)
    case Lint(contribution: LintContribution)

  /** Run every active reviewer plus the optional lint summariser concurrently,
    * emitting one Step per agent as it finishes. State is a parameter, not a
    * closure capture, so the parallel block never reads a moving var.
    * LLM-internal events emit from fork threads; [[OrcaListener]]
    * implementations must be thread-safe.
    *
    * The diff is sampled once per call so every reviewer in the round sees the
    * same payload. `declined` is every refusal the fixer has made and not since
    * fixed, delivered to this round's reviewers.
    */
  private def runReviewersAndLint(
      active: List[RosterEntry],
      currentState: ReviewLoopState,
      declined: List[IgnoredIssue]
  ): RoundOutcome =
    // A resumed reviewer that isn't handed the change set falls back to its own
    // `git diff HEAD`, which is empty as soon as the fixer commits — and an
    // empty diff reads as "nothing changed", so it reports clean without seeing
    // the fix.
    // Sampled here on the collecting thread, so the fan-out receives plain
    // data.
    val current =
      if active.isEmpty then DiffSample("", Nil) else diffSource.sample()

    // Each agent's key index is its position here — fixed before the fan-out.
    val reviewerTasks: List[() => AgentOutcome] =
      active.zipWithIndex.map: (e, agentIndex) =>
        val stored = currentState.sessions.get(e.id)
        () =>
          val (result, newSession) =
            reviewWithSession(e, stored, current, declined)
          AgentOutcome.Reviewer(
            RoundContribution(
              e,
              KeyedIssue.forAgent(agentIndex, result.issues),
              newSession
            )
          )

    // Resolved outside the fork below so the next state carries the
    // conversation even on a round that short-circuits before any turn; minting
    // one reserves an id and contacts nothing.
    val lintRound: Option[LintRound] = lintGate.map: gate =>
      val summariser = currentState.lintChat.getOrElse:
        // Group lint tokens under the same `reviewer` cost role as the
        // reviewers; the tagged copy stays local to this loop.
        Lint.summariser(
          gate.agent.withName(lintName).withRole(ReviewerPrompts.Role)
        )
      LintRound(gate, summariser)

    val lintTaskOpt: Option[() => AgentOutcome] =
      lintRound.map: r =>
        () =>
          val report =
            lint(r.gate.commands, r.summariser, ReviewLoopPrompts.SummariseLint)
          AgentOutcome.Lint(
            LintContribution(
              // Lint findings come last in the fix list, so it takes the index
              // after the last reviewer.
              KeyedIssue.forAgent(active.size, report.result.issues),
              report.resumableSummariser
            )
          )

    // The explicit type application is CC-forced: it widens both lists' element
    // type to `() => AgentOutcome` so their capture sets unify into the single
    // `C^` CheckedPar.mapParUnordered binds below. Deleting it breaks the CC
    // compile.
    val tasks = reviewerTasks.++[() => AgentOutcome](lintTaskOpt.toList)
    if tasks.isEmpty then RoundOutcome(Nil, currentState)
    else
      val outcomes: List[AgentOutcome] =
        // The fan out the file header's capture checking guards.
        CheckedPar.mapParUnordered(tasks.size)(tasks):
          // Display the bare slug — the `reviewer` role tag is a cost-report
          // grouping detail, not part of what the user sees.
          case AgentOutcome.Reviewer(c) =>
            ctx.emit(
              OrcaEvent.Step(formatReviewerOutcome(c.entry.name, c.issues))
            )
          case AgentOutcome.Lint(c) =>
            ctx.emit(
              OrcaEvent.Step(formatReviewerOutcome(lintName, c.issues))
            )
      collectRound(active, currentState, outcomes)

  /** Fold the round's completed outcomes into the next state and the issues the
    * fixer is handed. Reviewer contributions are put back into `active` order:
    * the fan-out completes unordered, and downstream output — the merged issue
    * list, the recorded `IgnoredIssues` — should not depend on which agent
    * happened to finish first.
    */
  private def collectRound(
      active: List[RosterEntry],
      currentState: ReviewLoopState,
      outcomes: List[AgentOutcome]
  ): RoundOutcome =
    val byId = outcomes.collect { case AgentOutcome.Reviewer(c) =>
      c.entry.id -> c
    }.toMap
    val contributions = active.flatMap(e => byId.get(e.id))
    val lint = outcomes.collectFirst { case AgentOutcome.Lint(c) => c }
    RoundOutcome(
      issues = contributions.flatMap(_.issues) ++ lint.toList.flatMap(_.issues),
      state = currentState.afterRound(
        contributions,
        lint.flatMap(_.resumableSummariser)
      )
    )

  /** Run the format commands, in order, in `ctx.workDir`. A nonzero exit is
    * reported as a `Step`; it stops neither the commands after it nor the
    * review, and repeats every round the command keeps failing.
    *
    * Takes [[WorkspaceWrite]] because it rewrites the tree (ADR 0018 §2.2), and
    * because that token is fork-opaque: moving this step into the reviewer
    * fan-out becomes a compile error rather than a race with the reviewers
    * reading the tree.
    */
  private def formatWorkspace()(using WorkspaceWrite): Unit =
    formatCommands.foreach: cmd =>
      val exitCode = runShell(cmd).exitCode
      if exitCode != 0 then
        ctx.emit(
          OrcaEvent.Step(s"format command failed (exit $exitCode): $cmd")
        )

  private def evaluate(
      state: ReviewLoopState,
      selectRound: List[ReviewBatch] -> List[RosterEntry],
      declined: List[IgnoredIssue]
  )(using WorkspaceWrite): RoundOutcome =
    // Format before reviewing so the implementation's and each fix's edits are
    // cleaned up before reviewers and the lint see them, and the committed tree
    // stays formatted.
    formatWorkspace()
    // The selector returns roster entries only, so no membership defence is
    // needed — just collapse an accidental duplicate so a reviewer runs at most
    // once per round. An empty selection stays empty: no reviewers run, the
    // round finds no issues, and the stop policy converges — the loop never
    // resurrects the roster behind the selector's back.
    val active = selectRound(state.history).distinctBy(_.id)
    // The same names the per-agent Steps use, in selection order with the lint
    // gate last; those Steps arrive in completion order, not this one.
    val agentNames =
      active.map(_.name) ++ Option.when(lintGate.isDefined)(lintName)
    if agentNames.nonEmpty then
      ctx.emit(
        OrcaEvent.Step(
          s"Running ${TextUtil.pluralize(agentNames.size, "review agent")}: " +
            agentNames.mkString(", ")
        )
      )
    // Say so when a round runs nobody though reviewers are configured: the
    // round then finds nothing and the loop converges, which is otherwise
    // indistinguishable from a clean review.
    if active.isEmpty && roster.nonEmpty then
      ctx.emit(
        OrcaEvent.Step("reviewer selection returned no reviewers this round")
      )
    runReviewersAndLint(active, state, declined)

  // Routed through the durable [[FlowSession]] door: a coder whose backend
  // conversation is fresh or lost gets the seed + progress preamble re-applied
  // and its learned wire id persisted.
  private def fix(issues: List[KeyedIssue])(using
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
    * `fc`/`ws` are method parameters, not fields — see the file header.
    */
  def run()(using fc: FlowControl, ws: WorkspaceWrite): IgnoredIssues =
    // A progress marker, not a committing stage: the enclosing implement-task
    // stage already names the work and owns the commit (ADR 0018 §2.2).
    orca.display("Review & fix")
    // Two-phase selection: run the selector's gated effects (e.g. the agentDriven
    // picker LLM call) ONCE here, at loop start, inside this stage. `selectRound`
    // is the resulting pure per-iteration narrowing, passed to `evaluate` so it
    // stays a function of its inputs. `ctx`/`ev` passed explicitly for the same
    // given-priority reason as the constructor call in `reviewAndFixLoop`.
    val selectRound: List[ReviewBatch] -> List[RosterEntry] =
      reviewerSelection.prepare(roster, task.title, diffSource.selectorFiles)(
        using
        ctx,
        ev
      )
    @scala.annotation.tailrec
    def loop(
        accumulated: IgnoredIssues,
        iteration: Int,
        state: ReviewLoopState,
        // Severity per finding evaluated so far, which [[IgnoredIssue]] doesn't
        // carry and the closing block needs.
        severities: Map[Title, Severity]
    ): IgnoredIssues =
      orca.display(s"Iteration ${iteration + 1}")
      // `accumulated` doubles as the cross-round decline set sent to this
      // round's reviewers: only declines reach it before an exit, minus any
      // since fixed. The whole set rather than the last round's, so a reviewer
      // first activated in round three still learns what was settled in round
      // one. Not split per reviewer — a [[FixOutcome]] doesn't say which
      // reviewer reported what — so every reviewer is sent the whole list.
      val round = evaluate(state, selectRound, accumulated.issues)
      val findings = round.issues.map(_.issue)
      val seenSeverities = indexSeverities(severities, findings)
      stopPolicy(
        findings,
        iteration = iteration,
        maxIterations = maxIterations
      ) match
        // `ctx` explicit on the announce calls for the same given-priority
        // reason as `selectRound`.
        case LoopStep.Done =>
          announceExit(
            cleanExitMessage(accumulated),
            accumulated,
            seenSeverities
          )(using
            ctx
          )
          accumulated
        case LoopStep.CapReached(ignored) =>
          val open = recordIgnored(accumulated, ignored.issues)
          announceExit(capExitMessage(maxIterations), open, seenSeverities)(
            using ctx
          )
          open
        case LoopStep.NeedsFix =>
          val outcome = FixOutcome.reconcile(round.issues, fix(round.issues))
          announceFixTurn(outcome)(using ctx)
          if outcome.fixed.isEmpty then
            val open = recordIgnored(accumulated, fixerHaltAdditions(outcome))
            announceExit(FixerHaltMessage, open, seenSeverities)(using ctx)
            open
          else
            loop(
              carryPastFixes(accumulated, outcome.fixed.toSet, outcome.ignored),
              iteration + 1,
              round.state,
              seenSeverities
            )
    loop(IgnoredIssues(Nil), 0, ReviewLoopState.empty, Map.empty)
