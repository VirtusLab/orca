package orca.review

// Compiled under capture checking so `prepare`'s returned narrowing can be a
// PURE `->` arrow — enforced at compile time not to capture `InStage`. Tapir
// `derives`/macro-expanding types don't type-check under CC, so
// [[ReviewerInfo]]/[[ReviewerSelectionRequest]] live in a sibling non-CC file.
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{FlowContext, InStage}
import orca.events.OrcaEvent
import orca.agents.Agent
import orca.plan.Title

import scala.util.matching.Regex

/** Picks which reviewers run on each iteration of [[reviewAndFixLoop]].
  *
  * Two-phase: [[prepare]] is called ONCE at loop start with the loop-constant
  * context (the roster as opaque [[RosterEntry]] handles, task title, changed
  * files); any gated effect (e.g. [[ReviewerSelector.agentDriven]]'s picker LLM
  * call) happens there, inside the loop's stage. It returns the pure
  * per-iteration narrowing: given the review history (most recent batch first),
  * which reviewers run this round.
  *
  * A selector can only ever return a subset/permutation of the [[RosterEntry]]
  * handles it was handed (the ctor is `private[review]`), so the loop needs no
  * runtime roster-membership defence.
  *
  * What `->` enforces on the returned arrow is that it captures no CAPABILITY —
  * in particular not [[orca.InStage]], so no round can drive an LLM or spend
  * tokens. Untracked values are outside that check: capturing the
  * [[orca.FlowContext]] compiles, and the shipped [[ReviewerSelector.default]]
  * does it to announce a per-round decision with `ctx.emit`. Implementers:
  * every GATED effect belongs in [[prepare]], which runs once, inside the
  * loop's stage; the arrow may narrow over `history` and say what it decided,
  * nothing more. Selector values stay reusable across loops either way.
  */
trait ReviewerSelector:
  def prepare(
      all: List[RosterEntry[?]],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]]

object ReviewerSelector:

  /** [[reviewAndFixLoop]]'s shipped default: [[agentDriven]] picks the set once
    * at loop start, and [[narrowingAcrossRounds]] then re-runs, each later
    * round, only the reviewers that reported something in the previous round
    * plus those whose file pattern matched the change set.
    *
    * Two ways back to more coverage: [[allEveryRound]] runs the whole roster
    * every round, and the parameterless [[agentDriven]] — no parentheses —
    * picks once and replays that pick every round. Either costs a turn per
    * reviewer per round; the reason narrowing is the default is that a reviewer
    * which found nothing usually finds nothing again, and it's the reviewer
    * that reported which has to check the fix it triggered.
    */
  val default: ReviewerSelector = narrowingAcrossRounds(agentDriven)

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]] =
      _ => all

  /** Asks a picker LLM which reviewers are worth running for a given task. The
    * parameterless form — `reviewAndFixLoop`'s default — resolves the picker at
    * loop start as [[orca.FlowContext.reviewAgent]]`.cheap`; the overload below
    * takes the picker (and optionally retuned prompts/descriptions) explicitly.
    * The selection is computed once, at loop start — task context doesn't
    * change mid-loop — and the returned per-round function replays it, ignoring
    * history.
    *
    * The picker sees each reviewer as a `(name, description)` pair.
    * `descriptions` defaults to [[ReviewerPrompts.descriptionsBySlug]]; supply
    * a custom map (keyed by bare slug) when overriding the default set. If the
    * picker would see all-empty descriptions, a one-time `Step` warning fires.
    *
    * The picker is handed the task title, the changed file names and those
    * descriptions, and runs under [[orca.agents.ToolSet.ReadOnly]] in the
    * flow's work dir. That gates edits everywhere but not the shell — codex's
    * read-only sandbox still runs commands, claude's plan mode doesn't — so the
    * default brief tells it to open the changed files rather than judge by
    * their paths, to use `git diff HEAD` only if the shell happens to be there,
    * and to include a reviewer whenever it is unsure.
    *
    * `filePatterns` is a code-side pre-filter applied before the LLM call:
    * reviewers whose pattern doesn't match any of the iteration's
    * `changedFiles` are dropped, so the picker can't pick them. The default
    * ([[ReviewerPrompts.filePatternsBySlug]]) constrains only reviewers that
    * declared a `files:` frontmatter entry. Only a reviewer whose pattern
    * positively fails to match is dropped ([[FileClaim.NoMatch]]): an empty
    * `changedFiles` says nothing about which files changed, and dropping on it
    * would remove the reviewer from the whole review.
    *
    * Pick a cheap model (e.g. `claude.haiku`) — the decision is small, though
    * the agent does open a few files to make it. Override `instructions` to
    * retune the selection brief.
    */
  def agentDriven: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] -> List[RosterEntry[?]] =
      agentDriven(ctx.reviewAgent.cheap).prepare(all, taskTitle, changedFiles)

  /** See the parameterless [[agentDriven]] above for the full description. Wrap
    * the result in [[narrowingAcrossRounds]] to also narrow across rounds, as
    * the loop's [[default]] does; on its own the pick is replayed every round.
    */
  def agentDriven(
      agent: Agent[?],
      instructions: String = ReviewLoopPrompts.SelectReviewers,
      descriptions: Map[String, String] = ReviewerPrompts.descriptionsBySlug,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsBySlug
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] -> List[RosterEntry[?]] =
      val claims =
        all.map(r => (r, fileClaim(filePatterns, r.name, changedFiles)))
      val unknown = claims.collect:
        case (r, FileClaim.Unknown) => r.name
      if unknown.nonEmpty then
        ctx.emit(
          OrcaEvent.Step(
            s"reviewer selection: no changed files to match against; keeping " +
              s"${unknown.size} file-gated reviewer(s) eligible " +
              s"(${unknown.mkString(", ")})"
          )
        )
      val eligible = claims.collect:
        case (r, claim) if offeredToPicker(claim) => r
      val infos = eligible.map: r =>
        ReviewerInfo(
          name = r.name,
          description = descriptions.getOrElse(r.name, "")
        )
      if eligible.nonEmpty && infos.forall(_.description.isEmpty) then
        ctx.emit(
          OrcaEvent.Step(
            "reviewer selection: no descriptions matched the supplied " +
              "reviewers (custom reviewers without matching description " +
              "keys?). The picker will see names only."
          )
        )
      val names =
        if eligible.isEmpty then Nil
        else
          // Read-only: the picker decides which reviewers to run and must not
          // edit files during selection (reading context is fine).
          agent.withReadOnly
            .resultAs[SelectedReviewers]
            .autonomous
            .run(
              ReviewerSelectionRequest(
                taskTitle = taskTitle,
                changedFiles = changedFiles,
                availableReviewers = infos,
                instructions = instructions
              ),
              emitPrompt = false
            )
            .names
      // Post-filter against `eligible`, not `all`, so a picker that hallucinates
      // a name pre-filtered out can't resurrect it.
      val selected = SelectedReviewers(names).pick(eligible)
      // Safety floor: the picker narrows the set, it can't skip review. If it
      // picks nothing while reviewers are eligible, fall back to all eligible
      // so a real change is never silently unreviewed.
      val active =
        if selected.isEmpty && eligible.nonEmpty then
          ctx.emit(
            OrcaEvent.Step(
              s"reviewer selection: picker returned no usable names; " +
                s"falling back to all ${eligible.size} eligible reviewer(s)"
            )
          )
          eligible
        else selected
      _ => active

  /** Wraps `base` so its pick narrows as the fix loop iterates: the first round
    * runs whatever `base` selects, and every later round keeps only those of
    * `base`'s reviewers that either reported an issue in the previous round or
    * declare a file pattern that matched the change set
    * ([[FileClaim.Matches]]). It filters `base`'s per-round result and nothing
    * else, so a reviewer `base` excluded is never resurrected.
    *
    * Two limits are deliberate. The match is against the change set sampled at
    * loop start — the per-round arrow is pure and cannot re-sample the diff —
    * so a file-pattern reviewer's exemption is decided once, for the whole
    * loop; when the diff is empty nothing is claimed and nobody is exempt. And
    * narrowing never empties the set: a round with no reviewer at all would let
    * the fixer keep editing unreviewed, since a lint gate keeps the loop going
    * through reviewer silence.
    *
    * `filePatterns` must be the map the picker itself pre-filtered on — when
    * wrapping [[agentDriven]]`(agent, filePatterns = m)`, pass the same `m`
    * here, or the two disagree about which reviewers are file-gated.
    */
  def narrowingAcrossRounds(
      base: ReviewerSelector,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsBySlug
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] -> List[RosterEntry[?]] =
      val basePick = base.prepare(all, taskTitle, changedFiles)
      // Loop-constant: `changedFiles` doesn't change round to round, so the
      // claims are settled once here rather than recomputed per round.
      val claimingReviewers = all.filter: e =>
        fileClaim(filePatterns, e.name, changedFiles) == FileClaim.Matches
      history =>
        val active = basePick(history)
        history.headOption match
          case None => active
          case Some(previous) =>
            val reported = previous.reviewersWithIssues
            val narrowed = active.filter: e =>
              reported.exists(_ eq e) || claimingReviewers.exists(_ eq e)
            // The `active.isEmpty` arm returns the same empty list the
            // fallback would; it is there to keep a base selector that picked
            // nobody from being announced as "re-running all 0 reviewer(s)".
            if narrowed.nonEmpty || active.isEmpty then narrowed
            else
              ctx.emit(
                OrcaEvent.Step(
                  s"reviewer selection: nothing was reported last round; " +
                    s"re-running all ${active.size} selected reviewer(s) " +
                    s"rather than reviewing nothing"
                )
              )
              active

  /** What a reviewer's `files:` frontmatter pattern says about a round's change
    * set. Named cases because the two questions asked of a pattern want
    * different answers when the change set is empty: eligibility keeps
    * [[Unknown]] (dropping there removes the reviewer from the review, and
    * nothing downstream can put it back), while narrowing exempts only
    * [[Matches]] (a dropped reviewer has already run, and re-running everything
    * whose claim is merely unproven is how narrowing stops narrowing).
    */
  private enum FileClaim:
    /** No pattern declared: the reviewer applies to any change. */
    case Ungated

    /** A pattern is declared and a changed file matches it. */
    case Matches

    /** A pattern is declared and no changed file matches it. */
    case NoMatch

    /** A pattern is declared but the change set is empty, so the diff says
      * nothing about which files changed — it is also empty when the work under
      * review is already committed.
      */
    case Unknown

  /** Whether a reviewer with this claim is offered to the picker. Exhaustive on
    * purpose: only a claim that positively fails ([[FileClaim.NoMatch]]) may
    * drop a reviewer from the review, so a case added later has to state which
    * side it falls on instead of defaulting into the ineligible bucket.
    */
  private def offeredToPicker(claim: FileClaim): Boolean =
    claim match
      case FileClaim.NoMatch                                         => false
      case FileClaim.Ungated | FileClaim.Matches | FileClaim.Unknown => true

  private def fileClaim(
      filePatterns: Map[String, Regex],
      name: String,
      changedFiles: List[String]
  ): FileClaim =
    filePatterns.get(name) match
      case None                            => FileClaim.Ungated
      case Some(_) if changedFiles.isEmpty => FileClaim.Unknown
      case Some(rx) =>
        if changedFiles.exists(rx.findFirstIn(_).isDefined) then
          FileClaim.Matches
        else FileClaim.NoMatch
