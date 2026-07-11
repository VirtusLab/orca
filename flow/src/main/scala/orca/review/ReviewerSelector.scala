package orca.review

// Compiled under capture checking so `prepare`'s returned narrowing can be
// declared as a PURE `->` arrow — enforcing at compile time that the
// selector's per-round function does not capture `InStage`. Keep
// tapir `derives`/macro-expanding types out of here (they don't type-check
// under CC); [[ReviewerInfo]]/[[ReviewerSelectionRequest]] live in a sibling
// non-CC file, as `FixRequest.scala` does.
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
  * files) and the loop's own capabilities — any gated effect (e.g.
  * [[ReviewerSelector.agentDriven]]'s picker LLM call) happens there, inside
  * the loop's stage. It returns the pure per-iteration narrowing: given the
  * review history (most recent batch first), which reviewers run this round.
  *
  * A selector can only ever return a subset/permutation of the [[RosterEntry]]
  * handles it was handed — a foreign agent is unrepresentable (the ctor is
  * `private[review]`), so the loop needs no runtime roster-membership defence.
  * The returned narrowing is a pure arrow (`->`): it captures nothing gated, so
  * selector values are freely reusable across loops.
  *
  * Implementers: any per-round effect (an LLM call, a shell-out, anything
  * needing `FlowContext`/`InStage`) must be hoisted into [[prepare]] itself —
  * the arrow it returns is capture-checked pure and may only narrow over the
  * `history` it's given each round.
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
    * introduced by a fix won't see the fix. Was the default before LLM-driven
    * selection landed; pass explicitly when you want this behaviour back.
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

  /** Asks `agent` to pick which reviewers are worth running for a given task.
    * The selection is computed once, in [[ReviewerSelector.prepare]] at loop
    * start — task context doesn't change mid-loop, so a single query answers
    * every round; the returned per-round function is pure (it just replays the
    * pick, ignoring history).
    *
    * `taskTitle` and `changedFiles` arrive at `prepare` from
    * `reviewAndFixLoop`; the call site only supplies the picker LLM (and
    * optionally tunes prompts/descriptions).
    *
    * The picker sees each reviewer as a `(name, description)` pair. By default
    * `descriptions` is [[ReviewerPrompts.descriptionsBySlug]], so users who
    * pass `allReviewers(...)` get rich purpose-aware selection without extra
    * wiring; supply a custom map (keyed by the reviewer's bare slug, e.g.
    * `"my-thing"`) when overriding the default set. If the picker would see
    * all-empty descriptions, a one-time `Step` warning fires so the
    * silent-name-only-selection failure mode is visible.
    *
    * `filePatterns` is a code-side pre-filter applied before the LLM call:
    * reviewers whose pattern doesn't match any of the iteration's
    * `changedFiles` are dropped, so the picker can't pick them. The default
    * uses [[ReviewerPrompts.filePatternsBySlug]] — only reviewers that declared
    * a `files:` frontmatter entry are constrained; everything else is offered
    * to the picker as-is.
    *
    * Pick a cheap model (e.g. `claude.haiku`); the request is small. Override
    * `instructions` to retune the selection brief.
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
      val eligible = all.filter: r =>
        filePatterns.get(r.name) match
          case None     => true
          case Some(rx) => changedFiles.exists(f => rx.findFirstIn(f).isDefined)
      val infos = eligible.map: r =>
        // Names are bare slugs; the cost-attribution prefix never reaches the
        // picker (it's applied only at the loop's emission edge), so there's
        // nothing to strip and no ambiguity in the `name: description` line.
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
          // Read-only: the picker only needs to decide which reviewers
          // to run; it should never edit files during the selection
          // turn. If the model reads context (Cargo.toml, etc.) to
          // make a better choice, that's fine.
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
            ._2
            .names
      // Post-filter against `eligible`, not `all`, so a picker that hallucinates
      // a name pre-filtered out can't resurrect it.
      val selected = SelectedReviewers(names).pick(eligible)
      // Safety floor: the picker is an optimisation that *narrows* the set, not
      // a gate that can skip review entirely. If it picks nothing (a refusal, a
      // hallucinated set that matches nothing) while reviewers are eligible,
      // fall back to all eligible so a real change is never silently unreviewed
      // — orca's contract is that AI-written code gets reviewed.
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
