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
    * `filePatterns` is a code-side pre-filter applied before the LLM call:
    * reviewers whose pattern doesn't match any of the iteration's
    * `changedFiles` are dropped, so the picker can't pick them. The default
    * ([[ReviewerPrompts.filePatternsBySlug]]) constrains only reviewers that
    * declared a `files:` frontmatter entry.
    *
    * Pick a cheap model (e.g. `claude.haiku`); the request is small. Override
    * `instructions` to retune the selection brief.
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

  /** See the parameterless [[agentDriven]] above for the full description. */
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
      val eligible = all.filter: r =>
        filePatterns.get(r.name) match
          case None     => true
          case Some(rx) => changedFiles.exists(f => rx.findFirstIn(f).isDefined)
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
