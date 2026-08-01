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
  * runtime roster-membership defence. The returned arrow captures nothing
  * gated, so selector values are freely reusable across loops. Implementers:
  * hoist any per-round effect into [[prepare]] itself.
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
    * plus the file-pattern reviewers whose files are in the change set.
    *
    * Pass [[allEveryRound]] instead when regression coverage matters more than
    * tokens: with narrowing, a reviewer that went quiet won't see the fixes
    * made after it stopped running.
    */
  val default: ReviewerSelector = narrowingAcrossRounds(agentDriven)

  /** First iteration runs every reviewer; subsequent rounds re-run only those
    * that found something last round. Saves API spend on consistently-quiet
    * reviewers; the trade-off is that a reviewer who'd catch a regression
    * introduced by a fix won't see the fix.
    */
  val onlyPreviouslyReporting: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]] =
      history =>
        history.headOption match
          case None        => all
          case Some(batch) => batch.reviewersWithIssues

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
    * declared a `files:` frontmatter entry. An empty `changedFiles` disables
    * the pre-filter entirely (see [[matches]]).
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
      val gatedByPattern = all.filter(r => filePatterns.contains(r.name))
      if changedFiles.isEmpty && gatedByPattern.nonEmpty then
        ctx.emit(
          OrcaEvent.Step(
            s"reviewer selection: no changed files to match against; keeping " +
              s"${gatedByPattern.size} file-gated reviewer(s) eligible " +
              s"(${gatedByPattern.map(_.name).mkString(", ")})"
          )
        )
      val eligible =
        all.filter(r => matches(filePatterns, r.name, changedFiles))
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
    * declare a `filePatterns` entry matching the change set.
    *
    * Rationale for the two survivors: a reviewer that reported is the one whose
    * finding the fixer just acted on, so it must re-check; a file-pattern
    * reviewer owns a domain the fixer keeps editing, so it stays on for
    * regression coverage. Everything else re-reads an unchanged verdict at full
    * per-round cost.
    *
    * The wrapper only ever filters `base`'s per-round result, so a reviewer
    * `base` excluded is never resurrected. `changedFiles` is the loop-constant
    * set sampled at loop start — the per-round arrow is pure and cannot
    * re-sample the diff — so a file-pattern reviewer's eligibility is decided
    * once for the whole loop.
    */
  def narrowingAcrossRounds(
      base: ReviewerSelector,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsBySlug
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[RosterEntry[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] -> List[RosterEntry[?]] =
      val basePick = base.prepare(all, taskTitle, changedFiles)
      // Loop-constant, so computed here rather than per round — and bound as a
      // local so the returned arrow captures a plain list, not this instance.
      val ownsChangedFiles = all
        .filter(e => filePatterns.contains(e.name))
        .filter(e => matches(filePatterns, e.name, changedFiles))
      history =>
        val active = basePick(history)
        history.headOption match
          case None => active
          case Some(previous) =>
            val reported = previous.reviewersWithIssues
            active.filter: e =>
              reported.exists(_ eq e) || ownsChangedFiles.exists(_ eq e)

  /** Whether `name`'s declared file pattern — if it declares one — matches the
    * change set. An EMPTY `changedFiles` means "which files changed is unknown"
    * (a diff is also empty when the work is already committed), not "no files
    * changed", so every reviewer matches: dropping a file-gated reviewer there
    * silently removes it from the review, and nothing downstream can put it
    * back.
    */
  private def matches(
      filePatterns: Map[String, Regex],
      name: String,
      changedFiles: List[String]
  ): Boolean =
    filePatterns.get(name) match
      case None => true
      case Some(rx) =>
        changedFiles.isEmpty || changedFiles.exists(rx.findFirstIn(_).isDefined)
