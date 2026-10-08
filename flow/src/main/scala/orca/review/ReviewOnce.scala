package orca.review

// Capture checked so the reviewer fan-out below is checked like the loop's
// (see ReviewLoop.scala's header).
import language.experimental.captureChecking
import language.experimental.separationChecking

import orca.{CheckedPar, FlowContext, FlowControl, InStage, OrcaFlowException}
import orca.events.OrcaEvent
import orca.plan.Task

/** Review `diff` once, without fixing anything: `reviewerSelection` picks from
  * `reviewers` (by default a cheap picker, after each reviewer's `files:`
  * filter), and the picked reviewers run concurrently. Reviewers get the same
  * first-round prompt as in [[reviewAndFixLoop]], whose parameters of the same
  * names this shares.
  *
  * Throws [[OrcaFlowException]] when `diff` has nothing to review against (a
  * [[ReviewDiff.WholeRun]] with no usable starting commit).
  */
def reviewOnce(
    reviewers: List[ReviewerAgent[?]],
    task: Task,
    diff: ReviewDiff,
    userRequest: Option[String] = None,
    reviewerSelection: ReviewerSelector = ReviewerSelector.agentDriven
)(using ctx: FlowContext, ev: InStage, fc: FlowControl): ReviewReport =
  val source = ReviewDiff.resolve(diff) match
    case Right(s) => s
    case Left(skipped) =>
      throw new OrcaFlowException(s"cannot review: ${skipped.describe}")
  val sample = source.sample()
  val framing = ReviewFraming(
    task,
    userRequest.getOrElse(ctx.userPrompt),
    source.coverage
  )
  val picked = reviewerSelection
    .prepare(RosterEntry.roster(reviewers), task.title, sample.paths)(Nil)
    .distinctBy(_.id)
  // Each reviewer's index keys its findings on screen, as in a loop round.
  val turns: List[() => (Int, ReviewerFindings)] =
    picked.zipWithIndex.map: (e, i) =>
      () =>
        val (result, _) =
          ReviewerTurn.run(e, None, sample, framing, open = Nil, round = 1)
        (i, ReviewerFindings(e.name.value, result.findings))
  val byReviewer =
    if turns.isEmpty then Nil
    else
      CheckedPar
        .mapParUnordered(turns.size.min(MaxConcurrentReviewTasks))(turns):
          (i, r) =>
            ctx.emit(
              OrcaEvent.Step(
                formatReviewerOutcome(
                  r.reviewer,
                  KeyedFinding.forAgent(i, r.findings)
                )
              )
            )
        .map(_._2)
  ReviewReport(task, sample.paths, byReviewer)
