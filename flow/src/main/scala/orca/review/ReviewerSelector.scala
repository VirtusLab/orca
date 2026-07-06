package orca.review

import orca.{FlowContext, InStage}
import orca.events.OrcaEvent
import orca.agents.{AgentInput, JsonData, Agent, given}
import orca.plan.Title

import scala.util.matching.Regex

/** Picks which reviewers run on each iteration of [[reviewAndFixLoop]].
  *
  * Two-phase: [[prepare]] is called ONCE at loop start with the loop-constant
  * context (roster, task title, changed files) and the loop's own capabilities
  * — any gated effect (e.g. [[ReviewerSelector.agentDriven]]'s picker LLM call)
  * happens there, inside the loop's stage. It returns the pure per-iteration
  * narrowing: given the review history (most recent batch first), which
  * reviewers run this round. Nothing is captured across loops, so selector
  * values are freely reusable.
  */
trait ReviewerSelector:
  def prepare(
      all: List[Agent[?]],
      taskTitle: Title,
      changedFiles: List[String]
  )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]]

object ReviewerSelector:

  /** First iteration runs every reviewer; subsequent rounds re-run only those
    * that found something last round. Saves API spend on consistently-quiet
    * reviewers; the trade-off is that a reviewer who'd catch a regression
    * introduced by a fix won't see the fix. Was the default before LLM-driven
    * selection landed; pass explicitly when you want this behaviour back.
    */
  val onlyPreviouslyReporting: ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]] =
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
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using FlowContext, InStage): List[ReviewBatch] => List[Agent[?]] =
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
    * `descriptions` is [[ReviewerPrompts.descriptionsByToolName]], so users who
    * pass `allReviewers(...)` get rich purpose-aware selection without extra
    * wiring; supply a custom map (keyed by the tool's prefixed name, e.g.
    * `"reviewer: my-thing"`) when overriding the default set. If the picker
    * would see all-empty descriptions, a one-time `Step` warning fires so the
    * silent-name-only-selection failure mode is visible.
    *
    * `filePatterns` is a code-side pre-filter applied before the LLM call:
    * reviewers whose pattern doesn't match any of the iteration's
    * `changedFiles` are dropped, so the picker can't pick them. The default
    * uses [[ReviewerPrompts.filePatternsByToolName]] — only reviewers that
    * declared a `files:` frontmatter entry are constrained; everything else is
    * offered to the picker as-is.
    *
    * Pick a cheap model (e.g. `claude.haiku`); the request is small. Override
    * `instructions` to retune the selection brief.
    */
  def agentDriven(
      agent: Agent[?],
      instructions: String = ReviewLoopPrompts.SelectReviewers,
      descriptions: Map[String, String] =
        ReviewerPrompts.descriptionsByToolName,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsByToolName
  ): ReviewerSelector = new ReviewerSelector:
    def prepare(
        all: List[Agent[?]],
        taskTitle: Title,
        changedFiles: List[String]
    )(using
        ctx: FlowContext,
        ev: InStage
    ): List[ReviewBatch] => List[Agent[?]] =
      val eligible = all.filter: r =>
        filePatterns.get(r.name) match
          case None     => true
          case Some(rx) => changedFiles.exists(f => rx.findFirstIn(f).isDefined)
      val infos = eligible.map: r =>
        ReviewerInfo(
          // Show the picker the bare slug, not the `reviewer: …`
          // cost-attribution prefix: the prefix plus the `name: description`
          // serialization made the name ambiguous (a `:`-in-name inside a
          // `:`-separated line), so the model echoed something that didn't
          // match and selection collapsed to zero. `pick` matches either
          // form back.
          name = ReviewerPrompts.stripNamePrefix(r.name),
          description = descriptions.getOrElse(r.name, "")
        )
      if eligible.nonEmpty && infos.forall(_.description.isEmpty) then
        ctx.emit(
          OrcaEvent.Step(
            "reviewer selection: no descriptions matched the supplied " +
              "reviewers (names lack the `reviewer: ` prefix from a " +
              "preset builder?). The picker will see names only."
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

private case class ReviewerInfo(name: String, description: String)
    derives JsonData

private case class ReviewerSelectionRequest(
    taskTitle: Title,
    changedFiles: List[String],
    availableReviewers: List[ReviewerInfo],
    instructions: String
) derives JsonData

private object ReviewerSelectionRequest:
  given AgentInput[ReviewerSelectionRequest] with
    def serialize(r: ReviewerSelectionRequest): String =
      val files = r.changedFiles.map(f => s"  - $f").mkString("\n")
      val reviewers = r.availableReviewers
        .map(ri => s"  - ${ri.name}: ${ri.description}")
        .mkString("\n")
      s"""Task: ${r.taskTitle}
         |
         |Changed files:
         |$files
         |
         |Available reviewers:
         |$reviewers
         |
         |${r.instructions}""".stripMargin
