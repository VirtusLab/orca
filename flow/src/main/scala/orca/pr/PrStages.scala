package orca.pr

import orca.{
  FlowContext,
  FlowControl,
  Staged,
  WorkspaceWrite,
  gatedStage,
  git,
  userPrompt
}
import orca.agents.{Agent, JsonData, given}
import orca.tools.{NoDefaultBase, PrHandle}

// The stages [[openPrFromBranch]] and [[openPrIfGitHub]] both run. They share
// names and recorded types, so a resume replays either function's stages.

private[pr] val PushStage: String = "Push branch"
private[pr] val SummariseStage: String = "Generate PR title and description"
private[pr] val CreateStage: String = "Open PR"

/** What the push stage records. */
private[pr] enum PushResult derives JsonData:
  case Pushed
  case Refused(reason: String)

  /** The refusal's reason on the left. */
  def outcome: Either[String, Unit] = this match
    case Pushed          => Right(())
    case Refused(reason) => Left(reason)

/** What the create stage records. */
private[pr] enum CreateResult derives JsonData:
  case Opened(pr: PrHandle)
  case Refused(reason: String)

  /** The refusal's reason on the left. */
  def outcome: Either[String, PrHandle] = this match
    case Opened(pr)      => Right(pr)
    case Refused(reason) => Left(reason)

/** A refusal as the step reports it. A recorded refusal replays on every resume
  * and is never retried; the line says so, since the user otherwise reads it as
  * this attempt's refusal.
  */
private[pr] def refusalLine(reason: String, from: Staged[?]): String =
  from match
    case Staged.Fresh(_) => reason
    case Staged.Replayed(_) =>
      s"$reason (recorded from the earlier attempt; orca will not retry)"

/** Summarise the branch-vs-`base` diff. A recorded summary replays without
  * resolving `base`; a fresh one stops with `base`'s `Left`.
  */
private[pr] def summarise(
    summarisingAgent: Agent[?],
    base: => Either[NoDefaultBase, String],
    context: Option[String],
    instructions: String
)(using FlowContext, FlowControl): Either[NoDefaultBase, PrSummary] =
  val (summaryContext, summaryInstructions) = context match
    case Some(c) => (c, instructions)
    case None =>
      (
        s"User prompt: $userPrompt",
        s"$instructions\n\n${PrPrompts.ClosingRefs}"
      )
  gatedStage(SummariseStage)(base): resolved =>
    summarisePr(
      agent = summarisingAgent,
      diff = git.diffVsBase(resolved),
      context = Some(summaryContext),
      instructions = summaryInstructions
    )
  .map(_.value)

/** Record `pr` as the run's published work, inside the create stage so its
  * commit carries the record.
  */
private[pr] def recordOpened(pr: PrHandle)(using
    FlowControl,
    WorkspaceWrite
): CreateResult =
  recordOpenedPr(pr)
  CreateResult.Opened(pr)
