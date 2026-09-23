package orca.pr

import orca.{
  FlowContext,
  FlowControl,
  OutsideStage,
  Staged,
  WorkspaceWrite,
  fail,
  gatedStage,
  gh,
  git,
  stage
}
import orca.agents.{Agent, JsonData, given}
import orca.review.OpenFindings
import orca.tools.{NoDefaultBase, PrHandle}

import ox.either.orThrow

/** Push the current feature branch and open a PR for it, as three separate
  * [[stage]]s: push → summarise → create. Split because a stage commits only on
  * completion, so pushing in the same stage as the summarise (or the preceding
  * edits) would be fragile on resume.
  *
  * Requires a GitHub remote and a logged-in `gh`: without them the push or the
  * create fails the run. A flow that should finish without a PR when the
  * checkout isn't on GitHub calls [[openPrIfGitHub]] instead.
  *
  * The diff handed to the summariser is the branch-vs-base diff
  * (`git.diffVsBase(git.defaultBase())`). A branch too large to summarise is
  * cut short by [[summarisePr]].
  *
  * Customise the PR text with `title`/`body`, both given the generated
  * [[PrSummary]]. Point `summarisingAgent` at a cheap model. `context` anchors
  * it to the originating issue or prompt. Omitted, it is the run's user prompt,
  * and the summariser adds `Closes` lines for the issues the prompt says to
  * fix. A flow that passes `context` adds its own `Closes` line through `body`
  * (`body = s => s"${s.body}\n\nCloses #42."`).
  *
  * `openFindings` is what the run's review loop returned still open; it goes
  * into the body after `body`'s text as its own section
  * ([[bodyWithOpenFindings]]), never through the summariser. Required, not
  * defaulted: a flow that forgets it would open a PR that says nothing about
  * findings it left unfixed. The same section is printed to the run output,
  * also when opening the PR fails.
  *
  * `gh.createPr` is idempotent by head branch: a re-run that already opened the
  * PR gets the existing handle back rather than failing. Returns that handle,
  * recorded as the run's published work through [[recordOpenedPr]] inside the
  * create stage.
  *
  * Does not compile inside a stage: opening the PR is a top-level step of a
  * flow, and this runs its own stages.
  */
def openPrFromBranch(
    summarisingAgent: Agent[?],
    openFindings: OpenFindings,
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using FlowContext, FlowControl, OutsideStage): PrHandle =
  reportOpenFindings(openFindings)
  // A refusal throws inside its stage, so it is never recorded and a resume
  // retries it. A recorded `Refused` is one [[openPrIfGitHub]] wrote.
  val push = stage(PushStage):
    git.push().orThrow
    PushAttempt.Pushed
  push match
    case PushAttempt.Refused(reason) => fail(reason)
    case PushAttempt.Pushed          => ()
  val summary = summarise(
    summarisingAgent,
    git.defaultBase(),
    context,
    instructions
  ).orThrow.value
  val create = stage(CreateStage):
    val pr = gh
      .createPr(
        title = title(summary),
        body = bodyWithOpenFindings(body(summary), openFindings)
      )
      .orThrow
    opened(pr)
  create match
    case CreateAttempt.Refused(reason) => fail(reason)
    case CreateAttempt.Opened(pr)      => pr

// The stage names, what they record and `summarise` are shared with
// [[openPrIfGitHub]], so a resume replays either function's stages.

private[pr] val PushStage: String = "Push branch"
private[pr] val SummariseStage: String = "Generate PR title and description"
private[pr] val CreateStage: String = "Open PR"

/** What the push stage records. */
private[pr] enum PushAttempt derives JsonData:
  case Pushed
  case Refused(reason: String)

/** What the create stage records. */
private[pr] enum CreateAttempt derives JsonData:
  case Opened(pr: PrHandle)
  case Refused(reason: String)

/** Summarise the branch-vs-`base` diff. A recorded summary replays without
  * resolving `base`; a fresh one stops with `base`'s `Left`.
  */
private[pr] def summarise(
    summarisingAgent: Agent[?],
    base: => Either[NoDefaultBase, String],
    context: Option[String],
    instructions: String
)(using
    ctx: FlowContext,
    control: FlowControl
): Either[NoDefaultBase, Staged[PrSummary]] =
  val (summaryContext, summaryInstructions) = context match
    case Some(c) => (c, instructions)
    case None =>
      (
        s"User prompt: ${ctx.userPrompt}",
        s"$instructions\n\n${PrPrompts.ClosingRefs}"
      )
  gatedStage(SummariseStage)(base): resolved =>
    summarisePr(
      agent = summarisingAgent,
      diff = git.diffVsBase(resolved),
      context = Some(summaryContext),
      instructions = summaryInstructions
    )

/** Record `pr` as the run's published work, inside the create stage so its
  * commit carries the record.
  */
private[pr] def opened(pr: PrHandle)(using
    FlowControl,
    WorkspaceWrite
): CreateAttempt =
  recordOpenedPr(pr)
  CreateAttempt.Opened(pr)
