package orca.pr

import orca.{
  FlowContext,
  FlowControl,
  OrcaFlowException,
  OutsideStage,
  WorkspaceWrite,
  gatedStage,
  gh,
  git,
  tracedStage
}
import orca.agents.Agent
import orca.events.OrcaEvent
import orca.progress.ThrowawayBranch
import orca.review.OpenFindings
import orca.tools.{GitHubAvailability, NoDefaultBase, PrHandle}
import orca.util.TextUtil

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
  * Whatever happens to the PR, `openFindings` is also printed to the run output
  * under the same heading as in the PR body.
  *
  * Parameters are [[openPrFromBranch]]'s, passed straight through —
  * `openFindings` included, so a PR opened here lists what the run's review
  * left open the same way. Like [[openPrFromBranch]], this is refused inside a
  * stage: opening the PR is a top-level step of a flow, and this runs its own
  * stages.
  */
def openPrIfGitHub(
    summarisingAgent: Agent[?],
    openFindings: OpenFindings,
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using
    ctx: FlowContext,
    control: FlowControl,
    outside: OutsideStage
): Option[PrHandle] =
  control.assertAtFlowBody("openPrIfGitHub(...)")
  reportOpenFindings(openFindings)
  lazy val base = git.defaultBase()
  pushThenCreate(
    base = base,
    summarisingAgent = summarisingAgent,
    title = title,
    // Composed once here, so the open findings reach the body without being
    // threaded through the legs below.
    body = summary => bodyWithOpenFindings(body(summary), openFindings),
    context = context,
    instructions = instructions
  ).fold(skipped, Some(_))

/** [[openPrFromBranch]]'s three stages, the two remote-facing legs under
  * [[attempt]], each recording its refusal as the stage's result. The summarise
  * stage is deliberately NOT wrapped: a summariser that fails or answers
  * unparseably is a failure of the run, not a GitHub answer this step should
  * absorb. `Left` is the line saying why no PR was opened.
  *
  * The pre-flight checks only read, so they gate the push stage rather than run
  * in it: a check that stops the step records nothing a resume would replay as
  * "done", and a resume whose push replays skips them, so a PR already opened
  * is not answered with "no PR".
  */
private def pushThenCreate(
    base: => Either[NoDefaultBase, String],
    summarisingAgent: Agent[?],
    title: PrSummary => String,
    body: PrSummary => String,
    context: Option[String],
    instructions: String
)(using FlowContext, FlowControl): Either[String, PrHandle] =
  for
    push <- gatedStage(PushStage)(preFlight(base))(_ => pushBestEffort())
    _ <- push.value.outcome.left.map(refusalLine(_, push))
    summary <- summarise(summarisingAgent, base, context, instructions).left
      .map(baseStopReason)
    create = tracedStage(CreateStage)(
      createBestEffort(title(summary), body(summary))
    )
    pr <- create.value.outcome.left.map(refusalLine(_, create))
  yield pr

/** `Left` is why a fresh run stops before its first write; `Right` goes ahead.
  * Announces where the PR will land once the checks pass, before the push: gh
  * takes the target from the checkout's remotes, not from what the run pushed.
  */
private def preFlight(base: => Either[NoDefaultBase, String])(using
    ctx: FlowContext,
    control: FlowControl
): Either[String, Unit] =
  val checked = for
    destination <- probe
    _ <- Either.cond(
      runChangedCode,
      (),
      "the run changed no code, no PR opened — nothing to review on a " +
        "branch that only carries orca's progress log"
    )
    _ <- base.left.map(baseStopReason)
  yield destination
  checked.map: destination =>
    import destination.{host, owner, repo}
    ctx.emit(OrcaEvent.Step(s"Opening a PR on $host/$owner/$repo"))

/** Where the PR will land, or why no PR can be opened. */
private def probe(using
    FlowContext
): Either[String, GitHubAvailability.Available] =
  gh.availability() match
    case available: GitHubAvailability.Available => Right(available)
    case GitHubAvailability.Unavailable(why) =>
      Left(
        s"${why.explanation}, no PR opened — push the branch and open the " +
          "PR yourself"
      )

private def baseStopReason(e: NoDefaultBase): String =
  s"cannot work out the base branch (${e.cause}), no PR opened — run " +
    "`git remote set-head origin -a` and open the PR yourself"

/** [[openPrFromBranch]]'s push with the push itself under [[attempt]]: the
  * stage machinery around it — the progress commit, the owner-thread assert —
  * still fails the run.
  */
private def pushBestEffort()(using FlowContext, WorkspaceWrite): PushAttempt =
  attempt(
    "could not push the branch",
    "push it yourself and open the PR from there"
  )(git.push())
    .fold(PushAttempt.Refused(_), _ => PushAttempt.Pushed)

/** [[openPrFromBranch]]'s create with the `gh pr create` under [[attempt]], as
  * [[pushBestEffort]] is for the push.
  */
private def createBestEffort(title: String, body: String)(using
    FlowContext,
    FlowControl,
    WorkspaceWrite
): CreateAttempt =
  attempt(
    "could not open a PR",
    "open it yourself from the pushed branch"
  )(gh.createPr(title = title, body = body)) match
    case Left(reason) => CreateAttempt.Refused(reason)
    // Outside `attempt`, so a log that cannot be written fails the run.
    case Right(pr) => recordOpened(pr)

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
  * run that cannot be measured (no readable header, or a starting commit git no
  * longer resolves) gets its PR; the lifecycle deletes a branch only when its
  * log records nothing published, so the two never strand a branch.
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
          ctx.runtimeGit,
          log.header.branchMode,
          startingCommit = log.header.startingCommit,
          featureBranch = log.header.branch
        )
      catch case NonFatal(_) => true

/** Report why no PR was opened — the run's single line for the skipped step.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
