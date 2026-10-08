package orca.review

import orca.InStage
import orca.agents.{Chat, PromptEvent}
import orca.plan.Task
import orca.review.diff.{
  DiffCoverage,
  DiffDelivery,
  DiffMessage,
  DiffSample,
  LastSent
}

/** One reviewer's live [[Chat]]. The chat bundles the role-tagged agent with
  * its conversation id, so a resume just calls the chat again.
  *
  * `lastSent` is the change set this reviewer was last sent, not the last one
  * sampled: a resume compares against it to decide whether there is anything
  * new to send ([[DiffDelivery.next]]).
  */
private[review] case class SessionEntry(chat: Chat[?], lastSent: LastSent)

/** What a reviewer's first prompt says about the work, constant for a review:
  * the task, what the user asked for, and what the diff covers.
  */
private[review] case class ReviewFraming(
    task: Task,
    userRequest: String,
    coverage: DiffCoverage
)

private[review] object ReviewerTurn:
  /** Run one reviewer over `current`, minting its [[Chat]] when `stored` is
    * empty so a later round can resume it. Returns the review result and the
    * [[SessionEntry]] to pass as `stored` next round. Touches no shared state,
    * so many can run in parallel.
    *
    * `open` is every finding still open, with its reason; a reviewer joining
    * after round one gets them too. `round` labels the trace only. The run
    * carries the `reviewer` cost role ([[ReviewerPrompts.Role]]).
    */
  def run(
      e: RosterEntry,
      stored: Option[SessionEntry],
      current: DiffSample,
      framing: ReviewFraming,
      open: List[OpenFinding],
      round: Int
  )(using InStage): (ReviewResult, SessionEntry) =
    val (chat, delivery) = stored match
      case Some(se) => (se.chat, DiffDelivery.next(se.lastSent, current))
      case None =>
        (
          e.agent.withRole(ReviewerPrompts.Role).chat(),
          DiffDelivery.first(current)
        )
    val prompt = promptFor(delivery.message, framing, open)
    ReviewLogging.review(e.name.value, round, delivery.message, prompt)
    val result =
      chat.resultAs[ReviewResult].autonomous.run(prompt, PromptEvent.Suppress)
    (result, SessionEntry(chat, delivery.lastSent))

  private def promptFor(
      message: DiffMessage,
      framing: ReviewFraming,
      open: List[OpenFinding]
  ): String =
    message match
      case DiffMessage.Initial(sample) =>
        ReviewLoopPrompts.initialReview(
          task = framing.task,
          userRequest = framing.userRequest,
          diff = sample.text,
          coverage = framing.coverage,
          open = open
        )
      case DiffMessage.ReReview(changes) =>
        ReviewLoopPrompts.reReview(changes, open)
