package orca.review

import orca.agents.AgentInput
import orca.review.diff.{DiffMessage, LastSent, ReReviewChanges}
import orca.safe.Logger

/** Records what each review turn was actually sent, at DEBUG.
  *
  * The review, fix and reviewer-picker turns all run with
  * `PromptEvent.Suppress` — a `▸` line per reviewer per round is noise on
  * screen — so no `UserPrompt` event exists for the runner's listener to
  * mirror, and without this the run's trace would hold no copy of what a
  * reviewer saw. Written straight to the `orca.flow` logger, which `OrcaLog`
  * makes non-additive, so none of it reaches the console.
  *
  * Kept out of `ReviewLoop.scala`/`ReviewerSelector.scala` so both can render
  * their prompt through the same [[AgentInput]] the agent call will use, which
  * needs no capture checking.
  */
private[review] object ReviewLogging:
  private val log = Logger("orca.flow")

  /** A reviewer's turn. The payload's shape is what a post-mortem needs first:
    * it says whether the reviewer was sent the whole change set, part of it, a
    * file list, or nothing at all.
    */
  def review(
      reviewer: String,
      round: Int,
      message: DiffMessage,
      prompt: String
  ): Unit =
    val (shape, chars, files) = message match
      case DiffMessage.Initial(sample) =>
        ("initial", sample.text.promptLength, sample.paths)
      case DiffMessage.ReReview(ReReviewChanges.Updated(diff)) =>
        ("updated", diff.length, Nil)
      case DiffMessage.ReReview(ReReviewChanges.Sections(diff, changed, _)) =>
        ("sections", diff.length, changed)
      case DiffMessage.ReReview(ReReviewChanges.Paths(paths)) =>
        ("paths", 0, paths)
      case DiffMessage.ReReview(ReReviewChanges.AlreadySeen(last)) =>
        (s"already-seen/${lastSentShape(last)}", 0, Nil)
    log.debug(
      s"review prompt: reviewer=$reviewer round=$round payload=$shape chars=$chars files=${joined(files)}\n$prompt"
    )

  /** The fix turn: which findings the coder was handed, then the prompt.
    *
    * Guarded rather than left to slf4j: rendering serialises the request a
    * second time, which is wasted whenever DEBUG is off.
    */
  def fix(request: FixRequest): Unit =
    log.debug(
      s"fix prompt: findings=${request.findings.size} keys=${joined(request.findings.map(_.key))}\n${render(request)}"
    )

  /** The reviewer picker's one call, which happens before any round. Guarded
    * for the same reason as [[fix]].
    */
  def reviewerPick(request: ReviewerSelectionRequest): Unit =
    log.debug(
      s"reviewer picker prompt: candidates=${joined(request.availableReviewers.map(_.name))}\n${render(request)}"
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
