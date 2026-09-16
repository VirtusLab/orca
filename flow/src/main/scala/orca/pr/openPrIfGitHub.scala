package orca.pr

import orca.{
  FlowContext,
  FlowControl,
  OrcaFlowException,
  OutsideStage,
  WorkspaceWrite,
  gh,
  git,
  stage
}
import orca.agents.{Agent, JsonData, given}
import orca.events.OrcaEvent
import orca.progress.ThrowawayBranch
import orca.tools.{GitHubAvailability, NoDefaultBase, PrHandle}
import orca.util.TextUtil

import ox.either.orThrow

import scala.util.control.NonFatal

/** [[openPrFromBranch]] where a PR can be opened, one reported line where it
  * can't — for a flow that should finish its work either way. Returns the
  * handle when a PR was opened, `None` when it wasn't.
  *
  * Best effort covers the whole step, not just the probe: a refused push, a
  * base branch git cannot resolve, and a `gh pr create` that comes back
  * empty-handed all end the same way — one `Step` naming the reason, `None`,
  * and a run that still succeeds. A flow that must have its PR calls
  * [[openPrFromBranch]], which throws instead.
  *
  * Parameters are [[openPrFromBranch]]'s, passed straight through. Like it,
  * this does not compile inside a stage: opening the PR is a top-level step of
  * a flow, and this runs its own stages.
  */
def openPrIfGitHub(
    summarisingAgent: Agent[?],
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using
    ctx: FlowContext,
    control: FlowControl,
    outside: OutsideStage
): Option[PrHandle] =
  lazy val base = git.defaultBase()
  // The pre-flight checks only read, so they must not record a stage a resume
  // would replay as "done" — and on a resume they are skipped entirely: the
  // push stage's recorded result decides the step, so a PR already opened is
  // not answered with "no PR".
  val push = provenance(PushStage)
  val stop = push match
    case Provenance.Replayed => None
    case Provenance.Fresh    => stopReason(base)
  stop match
    case Some(reason) => skipped(reason)
    case None =>
      pushThenCreate(
        base,
        push,
        summarisingAgent,
        title,
        body,
        context,
        instructions
      )

/** Why a fresh run stops before its first write, or `None` to go ahead.
  * Announces where the PR will land once the checks pass, before the push: gh
  * takes the target from the checkout's remotes, not from what the run pushed.
  */
private def stopReason(base: => Either[NoDefaultBase, String])(using
    ctx: FlowContext,
    control: FlowControl
): Option[String] =
  probe match
    case Probe.NoPr(reason) => Some(reason)
    case Probe.Destination(label) =>
      if !runChangedCode then
        Some(
          "the run changed no code, no PR opened — nothing to review on a " +
            "branch that only carries orca's progress log"
        )
      else
        baseStopReason(base) match
          case Some(reason) => Some(reason)
          case None =>
            ctx.emit(OrcaEvent.Step(s"Opening a PR on $label"))
            None

/** The probe's answer as the step reports it. */
private enum Probe:
  /** The `<host>/<owner>/<repo>` the PR will land on. */
  case Destination(label: String)

  /** Why no PR can be opened. */
  case NoPr(reason: String)

private def probe(using FlowContext): Probe =
  gh.availability() match
    case GitHubAvailability.Available(host, owner, repo) =>
      Probe.Destination(s"$host/$owner/$repo")
    case GitHubAvailability.Unavailable(why) =>
      Probe.NoPr(
        s"${why.explanation}, no PR opened — push the branch and open the " +
          "PR yourself"
      )

/** The reason the summarise stage could not diff against `base`, or `None`. A
  * recorded summarise stage replays without asking git, so `base` is not forced
  * — a resume with every stage recorded still re-records its PR.
  */
private def baseStopReason(base: => Either[NoDefaultBase, String])(using
    control: FlowControl
): Option[String] =
  if control.stageRecorded(SummariseStage) then None
  else
    base.left.toOption.map: e =>
      s"cannot work out the base branch (${e.cause}), no PR opened — run " +
        "`git remote set-head origin -a` and open the PR yourself"

/** [[openPrFromBranch]]'s three stages, the two remote-facing legs under
  * [[attempt]], each recording its refusal as the stage's result. The base is
  * re-checked after the push, since a resume enters here without
  * [[stopReason]]. The summarise stage is deliberately NOT wrapped: a
  * summariser that fails or answers unparseably is a failure of the run, not a
  * GitHub answer this step should absorb. `push` is where the push stage's
  * result comes from, read before the stage ran.
  */
private def pushThenCreate(
    base: => Either[NoDefaultBase, String],
    push: Provenance,
    summarisingAgent: Agent[?],
    title: PrSummary => String,
    body: PrSummary => String,
    context: Option[String],
    instructions: String
)(using
    ctx: FlowContext,
    control: FlowControl
): Option[PrHandle] =
  pushBestEffort() match
    case PushAttempt.Refused(reason) => skipped(refusalLine(reason, push))
    case PushAttempt.Pushed =>
      baseStopReason(base) match
        case Some(reason) => skipped(reason)
        case None =>
          val summary =
            summarise(summarisingAgent, base.orThrow, context, instructions)
          val create = provenance(CreateStage)
          createBestEffort(title(summary), body(summary)) match
            case CreateAttempt.Refused(reason) =>
              skipped(refusalLine(reason, create))
            case CreateAttempt.Opened(pr) => Some(pr)

/** Where a best-effort stage's result comes from: this run, or the record of an
  * earlier attempt. Read before the stage runs — afterwards it is always
  * recorded.
  */
private enum Provenance:
  case Fresh
  case Replayed

private def provenance(stage: String)(using control: FlowControl): Provenance =
  if control.stageRecorded(stage) then Provenance.Replayed
  else Provenance.Fresh

/** A recorded refusal replays on every resume and is never retried; the line
  * says so, since the user otherwise reads it as this run's attempt.
  */
private def refusalLine(reason: String, from: Provenance): String =
  from match
    case Provenance.Fresh => reason
    case Provenance.Replayed =>
      s"$reason (recorded from the earlier attempt; orca will not retry)"

/** What the best-effort push stage records. */
private enum PushAttempt derives JsonData:
  case Pushed
  case Refused(reason: String)

/** What the best-effort create stage records. */
private enum CreateAttempt derives JsonData:
  case Opened(pr: PrHandle)
  case Refused(reason: String)

/** [[pushBranch]] with the push itself under [[attempt]]: the stage machinery
  * around it — the progress commit, the owner-thread assert — still fails the
  * run.
  */
private def pushBestEffort()(using FlowContext, FlowControl): PushAttempt =
  stage(PushStage):
    attempt(
      "could not push the branch",
      "push it yourself and open the PR from there"
    )(git.push())
      .fold(PushAttempt.Refused(_), _ => PushAttempt.Pushed)

/** [[createPr]] with the `gh pr create` under [[attempt]], as
  * [[pushBestEffort]] is for the push.
  */
private def createBestEffort(title: String, body: String)(using
    FlowContext,
    FlowControl
): CreateAttempt =
  stage(CreateStage):
    attempt(
      "could not open a PR",
      "open it yourself from the pushed branch"
    )(gh.createPr(title = title, body = body))
      .fold(CreateAttempt.Refused(_), pr => opened(pr))

/** Record the handle before the stage records its own result, so one stage
  * commit carries both. Outside [[attempt]] on purpose: a log that cannot be
  * written fails the run.
  */
private def opened(pr: PrHandle)(using
    FlowControl,
    WorkspaceWrite
): CreateAttempt =
  recordOpenedPr(pr)
  CreateAttempt.Opened(pr)

/** Run one remote-facing leg, turning the refusal it returns or whatever it
  * throws into the line this step reports. `git.push` and `gh.createPr` answer
  * the refusals they recognise as a `Left`; the rest (auth, network, a rejected
  * ruleset, gh output that will not parse) throw, so anything non-fatal is
  * absorbed too. Only the message's first line is kept: the reason is spliced
  * into a single `Step`.
  */
private def attempt[E <: OrcaFlowException, T](what: String, next: String)(
    leg: => Either[E, T]
): Either[String, T] =
  def report(e: Throwable): String =
    val reason = TextUtil.throwableMessage(e, firstLineOnly = true)
    s"$what ($reason), no PR opened — $next"
  try leg.left.map(report)
  catch case NonFatal(e) => Left(report(e))

/** Whether this run's branch carries anything but orca's own bookkeeping —
  * [[ThrowawayBranch]]'s rule, over the progress header the lifecycle reads. A
  * run that cannot be measured (no readable header, or a start branch git no
  * longer resolves) gets its PR; the lifecycle never deletes a branch a PR was
  * opened from, so neither can strand the branch.
  */
private def runChangedCode(using
    ctx: FlowContext,
    control: FlowControl
): Boolean =
  control.progressStore
    .load()
    .forall: log =>
      try
        !ThrowawayBranch.isThrowaway(
          git,
          log.header.branchMode,
          startBranch = log.header.startingBranch,
          featureBranch = log.header.branch
        )
      catch case NonFatal(_) => true

/** Report why no PR was opened — the run's single line for the skipped step.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
