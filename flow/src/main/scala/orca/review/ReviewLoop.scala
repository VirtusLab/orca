package orca.review

// Compiled under capture checking (imports below) so CheckedPar's fan-out
// enforcement fires at its call site: the reviewer fan-out may capture the
// shared `InStage`, and an exclusive `FlowControl`/`WorkspaceWrite` capture is
// a compile error (ADR 0018 §6, pinned by `orca.CcNegativeCompileTest`). That
// is why `fc`/`ws` are method parameters rather than fields throughout this
// file, and why `ctx` is taken as its own given: a `FlowContext` derived from
// `fc` would carry `fc` into the fan-out. Tapir `derives`/macro types don't
// type-check under CC — keep them in a sibling non-CC file (see
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
import orca.plan.Task

import orca.agents.{Chat, PromptEvent}
import orca.events.OrcaEvent

import orca.util.TextUtil

/** How many of a round's agent turns run at once — the selected reviewers plus
  * the lint gate, which shares the budget. Each is an agent subprocess, so the
  * width is capped independently of the roster, which a project extends by
  * dropping files in `.orca/reviewers/` (ADR 0023). The gate is queued first,
  * so sharing the budget never postpones it.
  */
private[review] val MaxConcurrentReviewTasks: Int = 8

/** How many fix turns [[reviewAndFixLoop]] allows before giving up. Public
  * because the shipped flows pass the number at their call sites instead of
  * inheriting it, and a test checks those numbers against this one.
  */
val DefaultMaxFixTurns: Int = 3

/** How [[ReviewFixLoop.drive]] runs its rounds. */
private[review] enum LoopShape:
  /** Review, fix, and review again until a round is clean, the fixer fixes
    * nothing, or `maxFixTurns` fix turns have run — up to `maxFixTurns + 1`
    * rounds, since it counts fix turns, not rounds.
    */
  case Converge(maxFixTurns: Int)

  /** One review and at most one fix turn, whose fixes go unreviewed. */
  case SinglePass

  /** What follows a fix turn that fixed something. */
  def afterFix: AfterFixTurn = this match
    case Converge(_) => AfterFixTurn.ReviewAgain
    case SinglePass  => AfterFixTurn.Stop

/** The headline shown when a loop gives up at the cap. */
private[review] def capExitMessage(maxFixTurns: Int): String =
  s"Reached max fix turns ($maxFixTurns)"

/** The headline for a round that found nothing for the fixer. Only the run's
  * first round can claim the review came back clean: a later round reaches here
  * after earlier rounds put findings on the screen, whether or not any are
  * still open. The block underneath ([[formatOpenFindings]]) counts and names
  * whatever is.
  */
private[review] def cleanExitMessage(
    open: List[OpenFinding],
    round: Int
): String =
  if round == 1 && open.isEmpty then "No findings"
  else "No new findings"

/** The headline shown when a loop stops because the fixer reported no fixes. */
private[review] val FixerHaltMessage: String =
  "Fixer reported no fixes; ending review"

/** The headline for a single pass ([[reviewThenFix]]) that ended after its fix
  * turn, so that what was fixed goes unreviewed.
  */
private[review] val SinglePassMessage: String =
  "Fixes applied; not reviewing again"

/** End a loop: announce `headline`, then the closing block naming everything
  * `open` still holds and why ([[formatOpenFindings]]), and return `open` as
  * the loop's result.
  *
  * Every exit of the loop ends here, because the returned [[OpenFindings]] is
  * the one thing a run's callers discard — without this the record would exist
  * only in a value nobody reads. Intermediate rounds deliberately don't print
  * it: a finding declined in round one may well be fixed in round two.
  *
  * A loop that reaches an exit ran its review, so nothing was skipped.
  */
private def exitWith(
    headline: String,
    open: List[OpenFinding]
)(using FlowContext): OpenFindings =
  orca.display(headline)
  formatOpenFindings(open).foreach(orca.display)
  OpenFindings(open, skipped = None)

/** `existing` with `additions` merged in, keyed by [[FindingId]]: an id
  * `existing` already carries is refreshed with the latest reason in place, a
  * new one is appended, and duplicate ids within `additions` collapse to the
  * last.
  *
  * Every accumulation point in the loop goes through this, so a finding
  * declined in round one and re-declined — or later reported unaccounted for —
  * comes back as one entry saying the last thing known about it, never as two
  * entries with contradictory reasons.
  */
private[review] def recordOpen(
    existing: List[OpenFinding],
    additions: List[OpenFinding]
): List[OpenFinding] =
  val latest = additions.map(f => f.id -> f).toMap
  val existingIds = existing.map(_.id).toSet
  val refreshed = existing.map(f => latest.getOrElse(f.id, f))
  val added =
    additions.map(_.id).distinct.filterNot(existingIds.contains).map(latest)
  refreshed ++ added

/** `accumulated` with the findings the fixer fixed dropped, then `additions`
  * folded in ([[recordOpen]]).
  *
  * The loop prunes here, on the continue path, because this is the one point a
  * fix verdict is observed: an entry left in past it reports a finding that was
  * fixed as still open, and — since the carried set is also what the next
  * round's reviewers are shown — shows it to them too.
  *
  * The trade-off: a fixer that falsely claims a fix drops the entry from the
  * record unless a reviewer reports the finding again. That re-report is the
  * same recovery the continue path already relies on for `unaccounted` findings
  * ([[ReconciledFixOutcome]]).
  */
private[review] def carryPastFixes(
    accumulated: List[OpenFinding],
    fixed: Set[FindingId],
    additions: List[OpenFinding]
): List[OpenFinding] =
  recordOpen(accumulated.filterNot(f => fixed.contains(f.id)), additions)

/** What happens after a fix turn: a loop reviews the fixes, a single pass
  * ([[reviewThenFix]]) takes them on trust and stops. The announcement
  * ([[announceFixTurn]]) must not misreport it, and on `Stop` the fix turn
  * formats the fixer's edits itself, since the stage commits them next.
  */
private[review] enum AfterFixTurn:
  case ReviewAgain, Stop

/** Announce the fixer's replies that matched no finding it was handed — the
  * visible sign of a degraded fix turn, whose entries are otherwise dropped.
  */
private[review] def announceFixTurn(
    outcome: ReconciledFixOutcome,
    next: AfterFixTurn
)(using ctx: FlowContext): Unit =
  if outcome.unresolvedEchoes.nonEmpty then
    orca.display(
      s"Fixer named ${outcome.unresolvedEchoes.mkString(", ")}, " +
        "which matched no finding it was handed"
    )
  // A loop continues exactly when something was fixed, so only then does the
  // line promise another round; the halt branch prints FixerHaltMessage.
  val nextRound = next match
    case AfterFixTurn.ReviewAgain if outcome.fixed.nonEmpty =>
      "; reviewing again after the fixes"
    case _ => ""
  orca.display(
    s"Fixed ${outcome.fixed.size}, declined ${outcome.declined.size}$nextRound"
  )

/** One reviewer's live [[Chat]]. The chat bundles the role-tagged agent with
  * its conversation id, so a resume just calls the chat again.
  *
  * `lastSent` is the change set this reviewer was last sent, not the last one
  * sampled: a resume compares against it to decide whether there is anything
  * new to send ([[ReReviewChanges.of]]).
  */
private case class SessionEntry(chat: Chat[?], lastSent: LastSent)

/** All cross-round state for `reviewAndFixLoop`, in one immutable record.
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
        reviewers.map(c => (c.entry, ReviewResult(c.findings.map(_.finding))))
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
    findings: List[KeyedFinding],
    newSession: Option[SessionEntry]
)

/** What the lint command gate ([[Lint]], run alongside the reviewers)
  * contributed to a round: its keyed findings and the conversation [[lint]]
  * handed back as safe to resume.
  */
private case class LintContribution(
    findings: List[KeyedFinding],
    resumableSummariser: Option[Lint.Summariser]
)

/** The lint gate this round, paired with the conversation its summary runs on,
  * so neither can go missing without the other.
  */
private case class LintRound(gate: Lint, summariser: Lint.Summariser)

/** One review round's outcome: everything reported this round, keyed as the
  * fixer will see it, and the state to carry into the next round.
  */
private case class RoundOutcome(
    findings: List[KeyedFinding],
    state: ReviewLoopState
)

/** Run reviewers in parallel against `task`, gather per-reviewer outcomes, hand
  * every finding they report to the coder through `coderSession`'s seeded,
  * structured door, and loop. `reviewerSelection` decides which reviewers run
  * each round; the default narrows to the reviewers that reported last round,
  * so a reviewer that goes quiet won't see the fixes made after it stopped
  * running (see [[ReviewerSelector]]).
  *
  * `coderSession` is the coder's durable [[FlowSession]] (obtain it once with
  * `agent.session(name, seed)`). Each fix turn goes through
  * [[FlowSession.resultAs]]`.autonomous.run`, so a coder whose backend
  * conversation is fresh or lost-on-resume is re-primed with the recorded seed
  * and progress preamble, and its learned wire id is persisted.
  *
  * The fixer reports which findings it fixed and which it declined, with a
  * reason. The loop only re-evaluates when something was fixed — otherwise
  * there is nothing new for the reviewers to find, so the loop halts.
  *
  * Everything still open goes to the next round's reviewers, each with the
  * reason recorded for it, so a reviewer knows a finding was considered and
  * refused rather than missed — the one thing in the loop it could not have
  * worked out by reading the code. The `fixed` titles do not: a reviewer told
  * its finding was fixed is handed the answer it exists to work out for itself.
  * A refusal the fixer later reverses drops out of that set the round the fix
  * is observed.
  *
  * Nothing still open is lost at any exit: whatever the fixer declined or left
  * unaccounted for comes back in the returned [[OpenFindings]] with a reason,
  * and is printed at the exit.
  */
def reviewAndFixLoop(
    coderSession: FlowSession,
    reviewers: List[ReviewerAgent[?]],
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
    /** Which reviewers run each round — see [[ReviewerSelector]] for the
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
    /** Scala checks run each round before the reviewers, one at a time (see
      * [[ReviewCheck]]); their findings go to the fixer with the reviewers'.
      */
    checks: List[ReviewCheck] = Nil,
    /** How many fix turns before the loop gives up, folding whatever is still
      * open into the returned [[OpenFindings]]. Counts fix turns, not rounds —
      * see [[LoopShape.Converge]].
      */
    maxFixTurns: Int = DefaultMaxFixTurns,
    fixInstructions: String = ReviewLoopPrompts.Fix,
    /** Where the change set under review comes from: sampled from the enclosing
      * stage each round, sampled from where the whole run started, or pinned by
      * the caller. The choice changes what reviewers and the selector are told,
      * not just the diff text — see [[ReviewDiff]].
      */
    diff: ReviewDiff = ReviewDiff.SampleFromStage,
    /** What earlier reviews left open — typically the findings of per-task
      * [[reviewThenFix]] calls, handed to a whole-run final loop. They seed
      * this loop's open set: shown to reviewers from round one so
      * already-answered findings aren't re-reported from scratch, and returned
      * at exit (minus any since fixed) alongside this loop's own. Each keeps
      * the location the earlier loop recorded, so a seeded entry the exit block
      * names still points at the code. Two entries stay two unless they share
      * title and location, which makes them one defect.
      */
    priorOpenFindings: List[OpenFinding] = Nil
)(using
    ctx: FlowContext,
    ev: InStage,
    fc: FlowControl,
    ws: WorkspaceWrite
): OpenFindings =
  // `fc` is read here, at loop entry, so what the loop carries is the plain
  // commit hash rather than the capability it came from. `None` comes from
  // `WholeRun` alone: with no commit recorded there is nothing to diff against,
  // and reviewing some other range would be worse than not reviewing. The
  // ancestor probe runs here, at review time, not only when a resume bound the
  // branch: a rebase mid-run — and a fresh run's commit, which binding never
  // checked — would otherwise diff unrelated history.
  val diffSource: Option[ReviewDiffSource] = diff match
    case ReviewDiff.SampleFromStage =>
      Some(ReviewDiffSource.stage(ctx.git, fc.stageBaseCommit))
    case ReviewDiff.WholeRun =>
      fc.startingCommit
        .filter(ctx.git.isAncestorOfHead)
        .map: c =>
          ctx.emit(
            OrcaEvent.Step(
              s"reviewing everything changed since commit ${c.short}"
            )
          )
          ReviewDiffSource.wholeRun(ctx.git, c)
    case ReviewDiff.Pinned(d) => Some(ReviewDiffSource.Pinned(d))
  val seededOpen = IdentifiedFinding.withSeedIds(priorOpenFindings)
  diffSource match
    case None =>
      ctx.emit(
        OrcaEvent.Step(
          "skipping this review: the run has no usable starting commit to " +
            "diff the whole run against — its progress log records none, or " +
            "the one it records no longer sits behind HEAD (a rebase, or a " +
            "fresh clone). Start a fresh run if you need this review"
        )
      )
      // The seeds stay in: nothing after this loop reports them.
      OpenFindings(seededOpen, skipped = Some(SkippedReview.NoStartingCommit))
    case Some(source) =>
      new ReviewFixLoop(
        ReviewLoopConfig(
          coderSession = coderSession,
          reviewers = reviewers,
          reviewerSelection = reviewerSelection,
          task = task,
          userRequest = userRequest.getOrElse(ctx.userPrompt),
          formatCommands = resolveFormat(ctx, formatCommands),
          lintGate = resolveLint(ctx, lint),
          checks = checks,
          fixInstructions = fixInstructions,
          diffSource = source
        )
      ).drive(LoopShape.Converge(maxFixTurns), seededOpen)

/** One review round over the enclosing stage's changes and, if it found
  * anything, one fix turn — then done. The fixer's `fixed` claims are taken on
  * trust here, which is the trade [[reviewAndFixLoop]] exists to avoid making.
  * The one exception is the lint gate: machine-checkable, so it is re-run over
  * the fixer's edits and re-driven once if it still fails — reviewer findings
  * alone stay single-pass. Use this per task, where a later stage reviews the
  * same code again with fresh eyes — a whole-run final [[reviewAndFixLoop]],
  * say, which is what verifies these fixes. Pay for the loop where nothing else
  * re-reviews the result (ADR 0022 §2).
  *
  * Reviewers are picked once by [[ReviewerSelector.agentDriven]] and run once,
  * alongside the lint gate; see [[reviewAndFixLoop]] for what each of the
  * shared parameters means and how the review turns are framed.
  *
  * Nothing is silently dropped: what the fixer declined, what it never reported
  * on, and what the lint gate still reports after its own re-run, come back in
  * the returned [[OpenFindings]] with a reason and are printed at the exit.
  */
def reviewThenFix(
    coderSession: FlowSession,
    reviewers: List[ReviewerAgent[?]],
    task: Task,
    userRequest: Option[String] = None,
    formatCommands: Configured[List[String]] = Configured.FromSettings,
    lint: Configured[Lint] = Configured.FromSettings
)(using
    ctx: FlowContext,
    ev: InStage,
    fc: FlowControl,
    ws: WorkspaceWrite
): OpenFindings =
  new ReviewFixLoop(
    ReviewLoopConfig(
      coderSession = coderSession,
      reviewers = reviewers,
      reviewerSelection = ReviewerSelector.agentDriven,
      task = task,
      userRequest = userRequest.getOrElse(ctx.userPrompt),
      formatCommands = resolveFormat(ctx, formatCommands),
      lintGate = resolveLint(ctx, lint),
      checks = Nil,
      fixInstructions = ReviewLoopPrompts.Fix,
      diffSource = ReviewDiffSource.stage(ctx.git, fc.stageBaseCommit)
    )
  ).drive(LoopShape.SinglePass, priorOpen = Nil)

/** The format commands an entry point runs each round, resolved at entry (ADR
  * 0019) so what it hands the loop is plain data.
  */
private def resolveFormat(
    ctx: FlowContext,
    formatCommands: Configured[List[String]]
): List[String] =
  formatCommands match
    case Configured.FromSettings => ctx.stackSettings.format
    case Configured.Off          => Nil
    case Configured.Use(cs)      => cs

/** The lint gate an entry point runs alongside its reviewers, resolved at entry
  * like [[resolveFormat]]. Empty settings ≡ no gate: no `Lint` value is built
  * (and `ctx.reviewAgent` is not resolved), exactly like `Off`.
  */
private def resolveLint(
    ctx: FlowContext,
    lint: Configured[Lint]
): Option[Lint] =
  lint match
    case Configured.FromSettings =>
      Option.when(ctx.stackSettings.lint.nonEmpty)(
        Lint(ctx.stackSettings.lint, ctx.reviewAgent.cheap)
      )
    case Configured.Off    => None
    case Configured.Use(l) => Some(l)

/** What both entry points hand [[ReviewFixLoop]], so its constructor doesn't
  * mirror their parameters field-for-field. See [[reviewAndFixLoop]]'s
  * parameter docs for each field; `formatCommands` and `lintGate` hold values
  * `Configured` already resolved, and `userRequest` holds the caller's override
  * or, failing that, the run's `ctx.userPrompt`. The fix-turn cap is not here:
  * it belongs to [[LoopShape.Converge]], not to a round.
  */
private[review] case class ReviewLoopConfig(
    coderSession: FlowSession,
    reviewers: List[ReviewerAgent[?]],
    reviewerSelection: ReviewerSelector,
    task: Task,
    userRequest: String,
    formatCommands: List[String],
    lintGate: Option[Lint],
    checks: List[ReviewCheck],
    fixInstructions: String,
    diffSource: ReviewDiffSource
)

/** Implementation of [[reviewAndFixLoop]] and [[reviewThenFix]]: one instance
  * per invocation holds the loop-constant [[ReviewLoopConfig]] (fields imported
  * below), so the per-round logic reads as plain methods. Construct and call
  * [[drive]].
  *
  * All cross-round state lives in one immutable [[ReviewLoopState]] threaded
  * explicitly through [[drive]] (no captured `var`): reviewers fan out within a
  * round but each fork reads the snapshot it was handed and the next state is
  * computed once after they all return — no concurrent mutation.
  */
private[review] class ReviewFixLoop(
    config: ReviewLoopConfig
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
      open: List[OpenFinding],
      round: Int
  ): (ReviewResult, Option[SessionEntry]) =
    stored match
      case Some(se) => resumeReview(e, se, current, open, round)
      case None     => firstReview(e, current, open, round)

  /** Resume a reviewer's existing session, sending what is new to it since its
    * last round: the change set ([[ReReviewChanges]]) and the findings still
    * open, each with its reason. The run carries the `reviewer` cost role
    * ([[ReviewerPrompts.Role]]) so the `TokensUsed` breakdown can subtotal
    * reviewer spend, without renaming the entry's identity.
    *
    * The fixer's `fixed` titles are deliberately not sent — see
    * [[reviewAndFixLoop]].
    */
  private def resumeReview(
      e: RosterEntry,
      se: SessionEntry,
      current: DiffSample,
      open: List[OpenFinding],
      round: Int
  ): (ReviewResult, Option[SessionEntry]) =
    val changes = ReReviewChanges.of(se.lastSent, current)
    val prompt = ReviewLoopPrompts.reReview(changes, open)
    ReviewLogging.reReview(e.name.value, round, changes, prompt)
    val result =
      se.chat
        .resultAs[ReviewResult]
        .autonomous
        .run(prompt, PromptEvent.Suppress)
    // Nothing is sent on `AlreadySeen`, so the reviewer keeps comparing against
    // what it has seen. A cut round still records the whole sample, not what
    // was sent: the next round compares against all of it, so a change that
    // rewrites those files without adding or removing any must still register.
    val advanced = changes match
      case ReReviewChanges.Updated(_) =>
        Some(se.copy(lastSent = LastSent.inlined(current)))
      case ReReviewChanges.Sections(_, _, _) =>
        Some(se.copy(lastSent = LastSent.SectionsOnly(current)))
      case ReReviewChanges.Paths(_) =>
        Some(se.copy(lastSent = LastSent.PathsOnly(current)))
      case ReReviewChanges.AlreadySeen(_) => None
    (result, advanced)

  /** A reviewer's first call: mint a fresh [[Chat]] on the role-tagged agent so
    * a later round can resume it. `current` seeds the initial framing, and
    * `open` carries the findings left open so far to a reviewer joining after
    * round one.
    */
  private def firstReview(
      e: RosterEntry,
      current: DiffSample,
      open: List[OpenFinding],
      round: Int
  ): (ReviewResult, Option[SessionEntry]) =
    val chat = e.agent.withRole(ReviewerPrompts.Role).chat()
    val prompt = ReviewLoopPrompts.initialReview(
      task = task,
      userRequest = userRequest,
      diff = current.diff,
      diffIntro = diffSource.diffIntro,
      base = diffSource.base,
      open = open
    )
    ReviewLogging.initialReview(e.name.value, round, current, prompt)
    val result =
      chat.resultAs[ReviewResult].autonomous.run(prompt, PromptEvent.Suppress)
    (result, Some(SessionEntry(chat, LastSent.inlined(current))))

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
    * same payload. `open` is every finding still open — each with the reason
    * recorded for it — delivered to this round's reviewers.
    */
  private def runReviewersAndLint(
      active: List[RosterEntry],
      currentState: ReviewLoopState,
      open: List[OpenFinding]
  ): RoundOutcome =
    // A resumed reviewer that isn't handed the change set falls back to its own
    // `git diff HEAD`, which is empty as soon as the fixer commits — and an
    // empty diff reads as "nothing changed", so it reports clean without seeing
    // the fix.
    // Sampled here on the collecting thread, so the fan-out receives plain
    // data.
    val current =
      if active.isEmpty then DiffSample.empty else diffSource.sample()

    // Rounds already recorded, so this one is the next — the number the trace
    // labels each reviewer's prompt with.
    val round = currentState.history.size + 1

    // Each agent's key index is its position here — fixed before the fan-out.
    val reviewerTasks: List[() => AgentOutcome] =
      active.zipWithIndex.map: (e, agentIndex) =>
        val stored = currentState.sessions.get(e.id)
        () =>
          val (result, newSession) =
            reviewWithSession(e, stored, current, open, round)
          AgentOutcome.Reviewer(
            RoundContribution(
              e,
              KeyedFinding.forAgent(agentIndex, result.findings),
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
              KeyedFinding.forAgent(active.size, report.result.findings),
              report.resumableSummariser
            )
          )

    // The explicit type application is CC-forced: it widens both lists' element
    // type to `() => AgentOutcome` so their capture sets unify into the single
    // `C^` CheckedPar.mapParUnordered binds below. Deleting it breaks the CC
    // compile.
    //
    // Lint goes first because `mapParUnordered` draws in order: at the cap the
    // gate would otherwise wait for a reviewer to finish, and a round where the
    // two must make progress together would stall. `collectRound` keys
    // reviewers by id and picks lint out by type, so the order reaches nothing
    // downstream.
    val tasks = lintTaskOpt.toList.++[() => AgentOutcome](reviewerTasks)
    if tasks.isEmpty then RoundOutcome(Nil, currentState)
    else
      val outcomes: List[AgentOutcome] =
        // The fan out the file header's capture checking guards. The width is
        // fixed, not `tasks.size`: the roster is user-extensible (ADR 0023) and
        // each task is an agent subprocess. `collectRound` restores configured
        // order from completion order, so narrowing costs latency only.
        CheckedPar.mapParUnordered(tasks.size.min(MaxConcurrentReviewTasks))(
          tasks
        ):
          // Display the bare slug — the `reviewer` role tag is a cost-report
          // grouping detail, not part of what the user sees.
          case AgentOutcome.Reviewer(c) =>
            ctx.emit(
              OrcaEvent.Step(
                formatReviewerOutcome(c.entry.name.value, c.findings)
              )
            )
          case AgentOutcome.Lint(c) =>
            ctx.emit(
              OrcaEvent.Step(formatReviewerOutcome(lintName, c.findings))
            )
      collectRound(active, currentState, outcomes)

  /** Fold the round's completed outcomes into the next state and the findings
    * the fixer is handed. Reviewer contributions are put back into `active`
    * order: the fan-out completes unordered, and downstream output — the merged
    * finding list, the recorded `OpenFindings` — should not depend on which
    * agent happened to finish first.
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
      findings =
        contributions.flatMap(_.findings) ++ lint.toList.flatMap(_.findings),
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
  private def formatWorkspace()(using ws: WorkspaceWrite): Unit =
    ws.check("reviewFixLoop.formatWorkspace")
    formatCommands.foreach: cmd =>
      val exitCode = runShell(cmd).exitCode
      if exitCode != 0 then
        ctx.emit(
          OrcaEvent.Step(s"format command failed (exit $exitCode): $cmd")
        )

  /** Run the checks in order, emitting one Step per check as it finishes. Each
    * check's findings are keyed as one agent's, the first at `firstAgentIndex`.
    */
  private def runChecks(firstAgentIndex: Int): List[KeyedFinding] =
    if checks.nonEmpty then
      ctx.emit(
        OrcaEvent.Step(s"Running checks: ${checks.map(_.name).mkString(", ")}")
      )
    checks.zipWithIndex.flatMap: (check, i) =>
      val findings =
        KeyedFinding.forAgent(firstAgentIndex + i, check.evaluate().findings)
      ctx.emit(OrcaEvent.Step(formatReviewerOutcome(check.name, findings)))
      findings

  /** One review round: format the tree, run the checks, narrow the roster with
    * the prepared `selectRound`, and fan the active reviewers out alongside the
    * lint gate. Returns what they reported plus the state to carry forward — a
    * round is a function of the state it is handed, so it can be run once or in
    * a loop.
    *
    * `open` is what the round's reviewers are shown as still open, each with
    * the reason recorded for it.
    */
  private def evaluate(
      state: ReviewLoopState,
      selectRound: List[ReviewBatch] -> List[RosterEntry],
      open: List[OpenFinding]
  )(using WorkspaceWrite): RoundOutcome =
    // Format before reviewing so the implementation's and each fix's edits are
    // cleaned up before reviewers and the lint see them, and the committed tree
    // stays formatted.
    formatWorkspace()
    // The selector returns roster entries only, so no membership defence is
    // needed — just collapse an accidental duplicate so a reviewer runs at most
    // once per round. An empty selection stays empty: no reviewers run, the
    // round finds nothing, so the run ends — the loop never resurrects the
    // roster behind the selector's back.
    val active = selectRound(state.history).distinctBy(_.id)
    // Before the fan-out, so a check timing or building the code doesn't compete
    // with lint and the reviewers. Keyed after the reviewers and the lint gate,
    // the order the fixer is handed them in.
    val checkFindings = runChecks(active.size + lintGate.size)
    // The same names the per-agent Steps use, in selection order with the lint
    // gate last; those Steps arrive in completion order, not this one.
    val agentNames =
      active.map(_.name.value) ++ Option.when(lintGate.isDefined)(lintName)
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
    val reviewed = runReviewersAndLint(active, state, open)
    reviewed.copy(findings = reviewed.findings ++ checkFindings)

  // Routed through the durable [[FlowSession]] door: a coder whose backend
  // conversation is fresh or lost gets the seed + progress preamble re-applied
  // and its learned wire id persisted.
  private def fix(findings: List[IdentifiedFinding])(using
      fc: FlowControl,
      ws: WorkspaceWrite
  ): FixOutcome =
    val request = FixRequest(fixInstructions, findings.map(_.keyed))
    ReviewLogging.fix(request)
    coderSession.resultAs[FixOutcome].run(request, PromptEvent.Suppress)

  /** One fix turn over `findings`: hand them to the coder, reconcile its reply
    * against what it was handed ([[FixOutcome.reconcile]]), and announce the
    * result — `next` says whether the caller will review the fixes, which the
    * announcement must not get wrong. When no review follows, the fixer's edits
    * are formatted here, since no round will, and the enclosing stage is about
    * to commit them. What the outcome means for the run is the caller's
    * decision.
    *
    * `fc`/`ws` are method parameters, not fields — see the file header.
    */
  private def fixTurn(findings: List[IdentifiedFinding], next: AfterFixTurn)(
      using
      fc: FlowControl,
      ws: WorkspaceWrite
  ): ReconciledFixOutcome =
    val outcome = FixOutcome.reconcile(findings, fix(findings))
    announceFixTurn(outcome, next)
    next match
      case AfterFixTurn.Stop        => formatWorkspace()
      case AfterFixTurn.ReviewAgain => ()
    outcome

  /** Run the selector's gated effects (e.g. the
    * [[ReviewerSelector.agentDriven]] picker's LLM call) ONCE, at entry, inside
    * the caller's stage, and return the pure per-round narrowing [[evaluate]]
    * applies.
    */
  private def prepareSelection(): List[ReviewBatch] -> List[RosterEntry] =
    reviewerSelection.prepare(roster, task.title, diffSource.selectorFiles)

  /** Run [[evaluate]] and [[fixTurn]] rounds as `shape` says and return what is
    * left open, threading the immutable [[ReviewLoopState]] (reviewer history +
    * sessions) from round to round.
    *
    * A round that finds nothing ends the run, as does a fix turn that fixes
    * nothing ([[OpenReason.NoFixes]]). A converging loop also stops at its cap
    * ([[OpenReason.CapReached]]); a single pass stops after its one fix turn,
    * re-checking only the lint gate ([[relintAfterFix]]), and records what the
    * fixer did not report on as [[OpenReason.Unaccounted]], since no later
    * round can recover it.
    *
    * `priorOpen` starts the open set, each entry already given its id — see
    * [[reviewAndFixLoop]]'s `priorOpenFindings`.
    *
    * `fc`/`ws` are method parameters, not fields — see the file header.
    */
  def drive(
      shape: LoopShape,
      priorOpen: List[OpenFinding]
  )(using fc: FlowControl, ws: WorkspaceWrite): OpenFindings =
    // A progress marker, not a committing stage: the enclosing implement-task
    // stage already names the work and owns the commit (ADR 0018 §2.2).
    orca.display("Review & fix")
    // Two-phase selection: the pick happens once here, at start; what the
    // rounds get is the pure narrowing, passed to `evaluate` so a round stays a
    // function of its inputs.
    val selectRound: List[ReviewBatch] -> List[RosterEntry] = prepareSelection()
    @scala.annotation.tailrec
    def loop(
        accumulated: List[OpenFinding],
        round: Int,
        state: ReviewLoopState
    ): OpenFindings =
      // Only a loop numbers its rounds; a single pass has one.
      shape match
        case LoopShape.Converge(_) =>
          orca.display(s"Round $round")
        case LoopShape.SinglePass => ()
      // `accumulated` doubles as the open set sent to this round's reviewers:
      // the seeds plus the fixer's declines, minus any since fixed. The whole
      // set rather than the last round's, so a reviewer first activated in
      // round three still learns what was settled in round one. Not split per
      // reviewer — a [[FixOutcome]] doesn't say which reviewer reported what.
      val evaluated = evaluate(state, selectRound, accumulated)
      val findings = IdentifiedFinding.identify(
        round = round,
        open = accumulated,
        keyed = evaluated.findings
      )
      if findings.isEmpty then
        exitWith(cleanExitMessage(accumulated, round), accumulated)
      else
        shape match
          // Round N follows N - 1 fix turns.
          case LoopShape.Converge(max) if round > max =>
            exitWith(
              capExitMessage(max),
              recordOpen(
                accumulated,
                findings.map(_.open(OpenReason.CapReached(max)))
              )
            )
          case _ =>
            val next = shape.afterFix
            val outcome = fixTurn(findings, next)
            if outcome.fixed.isEmpty then
              exitWith(
                FixerHaltMessage,
                recordOpen(accumulated, outcome.stillOpen(OpenReason.NoFixes))
              )
            else
              next match
                case AfterFixTurn.ReviewAgain =>
                  loop(
                    carryPastFixes(
                      accumulated,
                      outcome.fixed.toSet,
                      outcome.declined
                    ),
                    round + 1,
                    evaluated.state
                  )
                case AfterFixTurn.Stop =>
                  exitUnreviewed(accumulated, outcome, evaluated.state, round)
    loop(priorOpen, 1, ReviewLoopState.empty)

  /** End a single pass after a fix turn that fixed something: its fixes go
    * unreviewed, so what the fixer did not report on stays open
    * ([[OpenReason.Unaccounted]]), and only the lint gate is re-checked
    * ([[relintAfterFix]]).
    */
  private def exitUnreviewed(
      accumulated: List[OpenFinding],
      outcome: ReconciledFixOutcome,
      state: ReviewLoopState,
      round: Int
  )(using fc: FlowControl, ws: WorkspaceWrite): OpenFindings =
    val fixTurnOpen = outcome.stillOpen(OpenReason.Unaccounted)
    // The re-check numbers its findings as the round after this one.
    val lintStillFailing =
      relintAfterFix(state, fixTurnOpen, round = round + 1)
        .map(_.open(OpenReason.LintStillFailing))
    exitWith(
      SinglePassMessage,
      recordOpen(accumulated, fixTurnOpen ++ lintStillFailing)
    )

  /** Re-run the lint gate over the fix turn's edits — the machine-checkable
    * check the single pass would otherwise skip, letting a fix that fails lint
    * (or doesn't compile) land in the stage's commit and break the tree later
    * tasks build on. A failure gets ONE fix turn scoped to it and one last
    * check; what still fails is returned — under a warning Step — as whole
    * findings, so the caller can record both the reason
    * ([[OpenReason.LintStillFailing]]) and where each points. Reviewer findings
    * stay single-pass — only this gate is re-driven, as the loop's rounds
    * re-drive it.
    *
    * `state` is the round's outcome state: its resumable lint conversation, if
    * any, is reused. `open` is what the fix turn left open, so a lint finding
    * the fixer declined that still fails keeps its id. `round` numbers the ids
    * of what the re-checks report. `fc`/`ws` are method parameters, not fields
    * — see the file header.
    */
  private def relintAfterFix(
      state: ReviewLoopState,
      open: List[OpenFinding],
      round: Int
  )(using fc: FlowControl, ws: WorkspaceWrite): List[IdentifiedFinding] =
    lintGate match
      case None => Nil
      case Some(gate) =>
        def freshSummariser(): Lint.Summariser =
          Lint.summariser(
            gate.agent.withName(lintName).withRole(ReviewerPrompts.Role)
          )
        def check(summariser: Lint.Summariser): LintReport =
          lint(gate.commands, summariser, ReviewLoopPrompts.SummariseLint)
        // Both checks share `round`: the first check's ids reach only its fix
        // turn, never the record.
        def identified(report: LintReport): List[IdentifiedFinding] =
          IdentifiedFinding.identify(
            round = round,
            open = open,
            keyed = KeyedFinding.forAgent(0, report.result.findings)
          )
        val recheck = check(state.lintChat.getOrElse(freshSummariser()))
        if recheck.result.findings.isEmpty then Nil
        else
          val findings = identified(recheck)
          ctx.emit(
            OrcaEvent.Step(
              formatReviewerOutcome(lintName, findings.map(_.keyed))
            )
          )
          val _ = fixTurn(findings, AfterFixTurn.Stop)
          // A reporting summariser is never resumable, so this is a fresh
          // conversation (see [[LintReport.resumableSummariser]]).
          val last = check(
            recheck.resumableSummariser.getOrElse(freshSummariser())
          )
          if last.result.findings.isEmpty then Nil
          else
            ctx.emit(
              OrcaEvent.Step(
                "warning: lint still fails after its fix turn — the stage " +
                  "commits with these findings open"
              )
            )
            identified(last)
