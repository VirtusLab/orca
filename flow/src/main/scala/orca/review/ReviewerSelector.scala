package orca.review

import orca.llm.{JsonData, LlmTool}
import orca.plan.Title

/** Picks which reviewers to run on each iteration of [[reviewAndFixLoop]].
  * `history` holds prior batches with the most recent first; `all` is the
  * originally configured set, useful for the very first iteration when there's
  * no history yet.
  */
type ReviewerSelector =
  (history: List[ReviewBatch], all: List[LlmTool[?]]) => List[LlmTool[?]]

object ReviewerSelector:

  /** Default. First iteration runs every reviewer; subsequent rounds re-run
    * only those that found something last round. Saves API spend on
    * consistently-quiet reviewers; the trade-off is that a reviewer who'd catch
    * a regression introduced by a fix won't see the fix.
    */
  val onlyPreviouslyReporting: ReviewerSelector = (history, all) =>
    history.headOption match
      case None        => all
      case Some(batch) => batch.reviewersWithIssues

  /** Costlier but thorough: every reviewer runs every iteration, regardless of
    * whether it's been quiet so far. Pick this when regression coverage matters
    * more than tokens.
    */
  val allEveryRound: ReviewerSelector = (_, all) => all

  /** Asks `llm` to pick which reviewers are worth running for a given task. The
    * selection is computed on the first call (when `all` is known) and cached
    * for subsequent iterations — `taskTitle` and `changedFiles` don't change
    * mid-loop, so re-querying the model would just burn tokens for the same
    * answer.
    *
    * Pick a cheap model (e.g. `claude.haiku`); the request is small. Override
    * `instructions` to retune the selection brief.
    */
  def llmDriven(
      llm: LlmTool[?],
      taskTitle: Title,
      changedFiles: List[String],
      instructions: String = ReviewLoopPrompts.SelectReviewers
  ): ReviewerSelector =
    var cached: Option[List[String]] = None
    (_, all) =>
      val names = cached.getOrElse:
        val picked = llm
          .resultAs[SelectedReviewers]
          .autonomous
          .run(
            ReviewerSelectionRequest(
              taskTitle = taskTitle,
              changedFiles = changedFiles,
              availableReviewers = all.map(_.name),
              instructions = instructions
            )
          )
          .names
        cached = Some(picked)
        picked
      SelectedReviewers(names).pick(all)

private case class ReviewerSelectionRequest(
    taskTitle: Title,
    changedFiles: List[String],
    availableReviewers: List[String],
    instructions: String
) derives JsonData
