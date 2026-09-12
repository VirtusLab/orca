package orca.pr

import orca.{FlowContext, FlowControl, OrcaFlowException, gh, git}
import orca.agents.Agent
import orca.events.OrcaEvent
import orca.progress.ThrowawayBranch
import orca.tools.{GitHubAvailability, PrHandle}
import orca.util.TextUtil

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
  * The probe (`gh.availability()`) and the has-anything-changed check run
  * outside any stage: they only read, and a run that skips the PR must not
  * leave a recorded stage that a resume would replay as "done". Neither runs
  * once the push stage is recorded: the branch is on the remote, so a resume
  * replays the stages and finishes the step — or reports what the remote now
  * refuses — rather than answering "no PR" for a PR it may already have opened.
  *
  * Parameters are [[openPrFromBranch]]'s, passed straight through.
  */
def openPrIfGitHub(
    summarisingAgent: Agent[?],
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using ctx: FlowContext, control: FlowControl): Option[PrHandle] =
  if control.stageRecorded(PushStage) then
    finishPushed(summarisingAgent, title, body, context, instructions)
  else
    gh.availability() match
      case GitHubAvailability.Available(host, owner, repo) =>
        openBestEffort(
          s"$host/$owner/$repo",
          summarisingAgent,
          title,
          body,
          context,
          instructions
        )
      case GitHubAvailability.NoRemote =>
        skipped(
          "no git remote, no PR opened — add a GitHub origin, then push the " +
            "branch and open the PR yourself"
        )
      case GitHubAvailability.NotGitHub(host) =>
        skipped(
          s"not a GitHub repository (origin is on $host), no PR opened — push " +
            s"the branch and open the PR yourself; if $host is a GitHub " +
            s"Enterprise host, `gh auth login --hostname $host` lets orca open " +
            "the next one"
        )
      case GitHubAvailability.NoHost(remote) =>
        skipped(
          s"origin is a local remote ($remote), no PR opened — point origin at " +
            "the GitHub repository, then push the branch and open the PR " +
            "yourself"
        )
      case GitHubAvailability.Unreachable(host, reason) =>
        skipped(
          s"cannot reach GitHub ($host), no PR opened — $reason; push the " +
            "branch and open the PR yourself when it is sorted"
        )

/** The push → summarise → create sequence against `destination`
  * (`<host>/<owner>/<repo>`, named before the first write because gh resolves
  * the base repo from the checkout's remotes), with the answers a best-effort
  * step owes the user instead of a failed run:
  *
  *   - nothing to open a PR for, so none is opened and none is announced;
  *   - no base branch to diff against;
  *   - a push or a create the remote refuses, see [[pushThenCreate]].
  */
private def openBestEffort(
    destination: String,
    summarisingAgent: Agent[?],
    title: PrSummary => String,
    body: PrSummary => String,
    context: Option[String],
    instructions: String
)(using ctx: FlowContext, control: FlowControl): Option[PrHandle] =
  if !runChangedCode then
    skipped(
      "the run changed no code, no PR opened — nothing to review on a branch " +
        "that only carries orca's progress log"
    )
  else
    baseBranch match
      case Left(reason) => skipped(reason)
      case Right(base) =>
        ctx.emit(OrcaEvent.Step(s"Opening a PR on $destination"))
        pushThenCreate(
          base,
          summarisingAgent,
          title,
          body,
          context,
          instructions
        )

/** The sequence for a resumed run whose push stage is recorded: the probe and
  * the changed-code check already passed on the attempt that pushed, and the
  * legs that remain answer for themselves.
  */
private def finishPushed(
    summarisingAgent: Agent[?],
    title: PrSummary => String,
    body: PrSummary => String,
    context: Option[String],
    instructions: String
)(using ctx: FlowContext, control: FlowControl): Option[PrHandle] =
  baseBranch match
    case Left(reason) => skipped(reason)
    case Right(base) =>
      pushThenCreate(base, summarisingAgent, title, body, context, instructions)

/** [[openPrFromBranch]]'s three stages with the two remote-facing legs under
  * [[attempt]]. Both legs report some refusals as values (branch protection,
  * "no commits") and throw the rest (no push permission, an expired credential,
  * no network, a ruleset violation); the stage that threw has already emitted
  * its own `OrcaEvent.Error`, so the user sees that error, then the reason, and
  * the run still succeeds.
  *
  * The summarise stage in between is deliberately NOT wrapped: a summariser
  * that fails or answers unparseably is a failure of the run, not a GitHub
  * answer this step should absorb.
  */
private def pushThenCreate(
    base: String,
    summarisingAgent: Agent[?],
    title: PrSummary => String,
    body: PrSummary => String,
    context: Option[String],
    instructions: String
)(using ctx: FlowContext, control: FlowControl): Option[PrHandle] =
  attempt(
    "could not push the branch",
    "push it yourself and open the PR from there"
  )(pushBranch()) match
    case Left(reason) => skipped(reason)
    case Right(()) =>
      val summary = summarise(summarisingAgent, base, context, instructions)
      attempt(
        "could not open a PR",
        "open it yourself from the pushed branch"
      )(createPr(title(summary), body(summary))) match
        case Left(reason) => skipped(reason)
        case Right(pr) =>
          recordOpenedPr(pr)
          Some(pr)

/** Run one remote-facing leg, turning whatever it throws into the line this
  * step reports. `git.push` and `gh.createPr` return a `Left` for the refusals
  * they recognise — which `.orThrow` rethrows as the [[OrcaFlowException]] it
  * is — and the rest of a leg's failures (auth, network, a rejected ruleset, gh
  * output that will not parse) throw their own exceptions, so anything
  * non-fatal is absorbed. Only the first line of the message is kept: the
  * reason is spliced into a single `Step`.
  */
private def attempt[T](what: String, next: String)(
    leg: => T
): Either[String, T] =
  try Right(leg)
  catch
    case NonFatal(e) =>
      val reason = TextUtil.throwableMessage(e, firstLineOnly = true)
      Left(s"$what ($reason), no PR opened — $next")

/** The branch `gh pr create` would open against, or the reason there is none:
  * `git.defaultBase()` throws when neither `origin/HEAD` nor `origin/main` nor
  * `origin/master` resolves, which is an environment answer rather than a
  * failure worth ending the run with. Read outside any stage, before the push.
  */
private def baseBranch(using FlowContext): Either[String, String] =
  try Right(git.defaultBase())
  catch
    case NonFatal(e: OrcaFlowException) =>
      Left(
        s"cannot work out the base branch (${TextUtil.throwableMessage(e)}), " +
          "no PR opened — run `git remote set-head origin -a` and open the PR " +
          "yourself"
      )

/** Whether this run's branch carries anything but orca's own bookkeeping —
  * [[ThrowawayBranch]]'s rule, over the same progress header the lifecycle
  * reads. A run with no readable header cannot be measured and gets its PR; the
  * lifecycle never deletes a branch a PR was opened from, so the two cannot
  * strand one between them.
  */
private def runChangedCode(using
    ctx: FlowContext,
    control: FlowControl
): Boolean =
  control.progressStore
    .load()
    .forall: log =>
      !ThrowawayBranch.isThrowaway(
        git,
        log.header.branchMode,
        startBranch = log.header.startingBranch,
        featureBranch = log.header.branch
      )

/** Report why no PR was opened, as the single line the run shows for the step
  * it didn't take.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
