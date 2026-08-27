package orca.review

import orca.agents.AgentInput
import org.slf4j.LoggerFactory

/** Records what each review turn was actually sent, at DEBUG.
  *
  * The review, fix and reviewer-picker turns all run with `emitPrompt = false`
  * — a `▸` line per reviewer per round is noise on screen — so no `UserPrompt`
  * event exists for the runner's listener to mirror, and without this the run's
  * trace would hold no copy of what a reviewer saw. Written straight to the
  * `orca.flow` logger, which `OrcaLog` makes non-additive, so none of it
  * reaches the console.
  *
  * Kept out of `ReviewLoop.scala`/`ReviewerSelector.scala` so both can render
  * their prompt through the same [[AgentInput]] the agent call will use, which
  * needs no capture checking.
  */
private[review] object ReviewLogging:
  private val log = LoggerFactory.getLogger("orca.flow")

  /** A reviewer's first turn: the change set it was handed, then the prompt. */
  def initialReview(
      reviewer: String,
      round: Int,
      sample: DiffSample,
      prompt: String
  ): Unit =
    log.debug(
      "review prompt: reviewer={} round={} payload=initial chars={} files={}\n{}",
      reviewer,
      round,
      sample.diff.length,
      joined(sample.paths),
      prompt
    )

  /** A resumed reviewer's turn. The payload's shape is what a post-mortem needs
    * first: it says whether the reviewer was sent the change set, part of it, a
    * file list, or nothing at all.
    */
  def reReview(
      reviewer: String,
      round: Int,
      changes: ReReviewChanges,
      prompt: String
  ): Unit =
    val (shape, chars, files) = changes match
      case ReReviewChanges.Updated(diff) => ("updated", diff.length, Nil)
      case ReReviewChanges.TooLarge(sections, changed, _) =>
        val shape = if sections.isDefined then "sections" else "paths"
        (shape, sections.fold(0)(_.length), changed)
      case ReReviewChanges.AlreadySeen(last) =>
        (s"already-seen/${lastSentShape(last)}", 0, Nil)
    log.debug(
      "review prompt: reviewer={} round={} payload={} chars={} files={}\n{}",
      reviewer,
      round,
      shape,
      chars,
      joined(files),
      prompt
    )

  /** The fix turn: which findings the coder was handed, then the prompt. */
  def fix(request: FixRequest): Unit =
    log.debug(
      "fix prompt: findings={} keys={}\n{}",
      request.issues.size,
      joined(request.issues.map(_.key)),
      render(request)
    )

  /** The reviewer picker's one call, which happens before any round. */
  def reviewerPick(request: ReviewerSelectionRequest): Unit =
    log.debug(
      "reviewer picker prompt: candidates={}\n{}",
      joined(request.availableReviewers.map(_.name)),
      render(request)
    )

  private def render[A: AgentInput](input: A): String =
    summon[AgentInput[A]].serialize(input)

  private def joined(names: List[String]): String =
    if names.isEmpty then "(none)" else names.mkString(", ")

  private def lastSentShape(last: LastSent): String = last match
    case LastSent.Inline(_)       => "inline"
    case LastSent.SectionsOnly(_) => "sections"
    case LastSent.PathsOnly(_)    => "paths"
    case LastSent.NoteOnly(_)     => "note"
