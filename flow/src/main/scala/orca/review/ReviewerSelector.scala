package orca.review

import orca.{FlowContext, InStage}
import orca.events.OrcaEvent
import orca.llm.{AgentInput, JsonData, LlmTool, given}
import orca.plan.Title

import scala.util.matching.Regex

/** Picks which reviewers to run on each iteration of [[reviewAndFixLoop]].
  *
  *   - `history` holds prior batches with the most recent first.
  *   - `all` is the originally configured reviewer set, useful for the very
  *     first iteration when there's no history yet.
  *   - `taskTitle` and `changedFiles` come from `reviewAndFixLoop`'s `task` and
  *     the diff it sampled at loop entry. They're passed through on every call
  *     so that selectors which need them (e.g. [[llmDriven]]) don't have to be
  *     reconstructed per task.
  */
type ReviewerSelector = (
    history: List[ReviewBatch],
    all: List[LlmTool[?]],
    taskTitle: Title,
    changedFiles: List[String]
) => List[LlmTool[?]]

object ReviewerSelector:

  /** First iteration runs every reviewer; subsequent rounds re-run only those
    * that found something last round. Saves API spend on consistently-quiet
    * reviewers; the trade-off is that a reviewer who'd catch a regression
    * introduced by a fix won't see the fix. Was the default before LLM-driven
    * selection landed; pass explicitly when you want this behaviour back.
    */
  val onlyPreviouslyReporting: ReviewerSelector = (history, all, _, _) =>
    history.headOption match
      case None        => all
      case Some(batch) => batch.reviewersWithIssues

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = (_, all, _, _) => all

  /** Asks `llm` to pick which reviewers are worth running for a given task. The
    * selection is computed on the first call and cached for subsequent
    * iterations — task context doesn't change mid-loop, so re-querying the
    * model would just burn tokens for the same answer.
    *
    * `taskTitle` and `changedFiles` arrive on each invocation from
    * `reviewAndFixLoop`; the call site only supplies the picker LLM (and
    * optionally tunes prompts/descriptions).
    *
    * **Single-loop scope.** The returned selector closes over a per-instance
    * cache. Reusing one selector across two `reviewAndFixLoop` invocations for
    * different tasks would yield iteration 1's pick on both. Build a fresh
    * selector per loop.
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
  def llmDriven(
      llm: LlmTool[?],
      instructions: String = ReviewLoopPrompts.SelectReviewers,
      descriptions: Map[String, String] =
        ReviewerPrompts.descriptionsByToolName,
      filePatterns: Map[String, Regex] = ReviewerPrompts.filePatternsByToolName
  )(using ctx: FlowContext, ev: InStage): ReviewerSelector =
    var cached: Option[List[String]] = None
    (_, all, taskTitle, changedFiles) =>
      val eligible = all.filter: r =>
        filePatterns.get(r.name) match
          case None     => true
          case Some(rx) => changedFiles.exists(f => rx.findFirstIn(f).isDefined)
      val names = cached.getOrElse:
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
        val picked =
          if eligible.isEmpty then Nil
          else
            // Read-only: the picker only needs to decide which reviewers
            // to run; it should never edit files during the selection
            // turn. If the model reads context (Cargo.toml, etc.) to
            // make a better choice, that's fine.
            llm.withReadOnly
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
        cached = Some(picked)
        picked
      // Post-filter against `eligible`, not `all`, so a picker that hallucinates
      // a name pre-filtered out can't resurrect it.
      val selected = SelectedReviewers(names).pick(eligible)
      // Safety floor: the picker is an optimisation that *narrows* the set, not
      // a gate that can skip review entirely. If it picks nothing (a refusal, a
      // hallucinated set that matches nothing) while reviewers are eligible,
      // fall back to all eligible so a real change is never silently unreviewed
      // — orca's contract is that AI-written code gets reviewed.
      if selected.isEmpty && eligible.nonEmpty then
        ctx.emit(
          OrcaEvent.Step(
            s"reviewer selection: picker returned no usable names; " +
              s"falling back to all ${eligible.size} eligible reviewer(s)"
          )
        )
        eligible
      else selected

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
