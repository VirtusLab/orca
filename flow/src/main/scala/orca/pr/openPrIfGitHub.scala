package orca.pr

import orca.{
  FlowContext,
  FlowControl,
  OrcaFlowException,
  OutsideStage,
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

/** [[openPrFromBranch]] where a PR can be opened, and nothing but one reported
  * line where it can't — for a flow that should finish its work either way.
  * Returns the handle when a PR was opened, `None` when it wasn't.
  *
  * Best effort covers the whole step, not just the probe: a refused push, a
  * base branch git cannot resolve, and a `gh pr create` that comes back
  * empty-handed all end the same way — one `Step` naming the reason, `None`,
  * and a run that still succeeds. A flow that must have its PR calls
  * [[openPrFromBranch]], which throws instead.
  *
  * The checks before the first write — the probe (`gh.availability()`), the
  * has-anything-changed check and the base branch — run outside any stage: they
  * only read, and a run that skips the PR must not leave a recorded stage that
  * a resume would replay as "done". They run only while the push stage is
  * unrecorded: a resume replays that stage's result instead — the push it made,
  * or the refusal it recorded — so it finishes the step rather than answering
  * "no PR" for a PR it may already have opened.
  *
  * Parameters are [[openPrFromBranch]]'s, passed straight through. Records the
  * handle like [[openPrFromBranch]], so it does not compile inside a stage.
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
  // Resolved at most once, and only by a check or a stage that runs.
  lazy val base = git.defaultBase()
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

/** Why a fresh run stops before its first write, or `None` to go ahead. Where
  * the PR will land is announced once every check has passed, and before the
  * push, because gh resolves the target from the checkout's remotes rather than
  * from anything the run wrote.
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

/** The reason the summarise stage could not diff against `base`, or `None` —
  * without forcing `base` when that stage is recorded: its recorded summary
  * replays and git is not asked, so a resume with every stage recorded still
  * re-records the PR it opened.
  */
private def baseStopReason(base: => Either[NoDefaultBase, String])(using
    control: FlowControl
): Option[String] =
  if control.stageRecorded(SummariseStage) then None
  else
    base.left.toOption.map: e =>
      s"cannot work out the base branch (${e.cause}), no PR opened — run " +
        "`git remote set-head origin -a` and open the PR yourself"

/** [[openPrFromBranch]]'s three stages with the two remote-facing legs under
  * [[attempt]], each recording its refusal as the stage's result. The base is
  * checked after the push, since a resume enters here without [[stopReason]].
  * The summarise stage is deliberately NOT wrapped: a summariser that fails or
  * answers unparseably is a failure of the run, not a GitHub answer this step
  * should absorb. `push` is where the push stage's result comes from, read
  * before the stage ran.
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
    control: FlowControl,
    outside: OutsideStage
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
            case CreateAttempt.Opened(pr) =>
              recordOpenedPr(pr)
              Some(pr)

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
      .fold(CreateAttempt.Refused(_), CreateAttempt.Opened(_))

/** Run one remote-facing leg, turning the refusal it returns or whatever it
  * throws into the line this step reports. `git.push` and `gh.createPr` answer
  * the refusals they recognise as a `Left`; the rest of a leg's failures (auth,
  * network, a rejected ruleset, gh output that will not parse) throw their own
  * exceptions, so anything non-fatal is absorbed too. Only the first line of
  * the message is kept: the reason is spliced into a single `Step`.
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
  * [[ThrowawayBranch]]'s rule, over the same progress header the lifecycle
  * reads. A run that cannot be measured — no readable header, or a start branch
  * git no longer resolves — gets its PR; the lifecycle never deletes a branch a
  * PR was opened from, so the two cannot strand one between them.
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

/** Report why no PR was opened, as the single line the run shows for the step
  * it didn't take.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
