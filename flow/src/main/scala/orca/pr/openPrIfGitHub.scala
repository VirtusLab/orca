package orca.pr

import orca.{FlowContext, FlowControl, OrcaFlowException, gh, git}
import orca.agents.Agent
import orca.events.OrcaEvent
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
  * leave a recorded stage that a resume would replay as "done".
  *
  * Parameters are [[openPrFromBranch]]'s, passed straight through.
  */
def openPrIfGitHub(
    summarisingAgent: Agent[?],
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using FlowContext, FlowControl): Option[PrHandle] =
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
        "no git remote, no PR opened — add a GitHub origin to have orca open one"
      )
    case GitHubAvailability.NotGitHub(host) =>
      skipped(
        s"not a GitHub repository (origin is on $host), no PR opened — if " +
          s"that is a GitHub Enterprise host, run `gh auth login --hostname " +
          s"$host` and re-run"
      )
    case GitHubAvailability.NoHost(remote) =>
      skipped(
        s"origin is a local remote ($remote), no PR opened — point origin at " +
          "a GitHub repository to have orca open one"
      )
    case GitHubAvailability.Unreachable(host, reason) =>
      skipped(
        s"cannot reach GitHub ($host), no PR opened — $reason; open the PR " +
          "yourself from the branch when it is sorted"
      )

/** The push → summarise → create sequence against `destination`
  * (`<host>/<owner>/<repo>`, named before the first write because gh resolves
  * the base repo from the checkout's remotes), with the answers a best-effort
  * step owes the user instead of a failed run:
  *
  *   - nothing to open a PR for, so none is opened and none is announced;
  *   - no base branch to diff against;
  *   - a push or a create the remote refuses. Both legs report some refusals as
  *     values (branch protection, "no commits") and throw the rest (no push
  *     permission, an expired credential, no network, a ruleset violation), so
  *     [[attempt]] takes either shape. The stage that threw has already emitted
  *     its own `OrcaEvent.Error`, so the user sees that error, then the reason,
  *     and the run still succeeds.
  *
  * The summarise stage in between is deliberately NOT wrapped: a summariser
  * that fails or answers unparseably is a failure of the run, not a GitHub
  * answer this step should absorb.
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
        attempt(
          "could not push the branch",
          "push it yourself and open the PR from there"
        )(pushBranch()) match
          case Left(reason) => skipped(reason)
          case Right(()) =>
            val summary =
              summarise(summarisingAgent, base, context, instructions)
            attempt(
              "could not open a PR",
              "open it yourself from the pushed branch"
            )(createPr(title(summary), body(summary))) match
              case Left(reason) => skipped(reason)
              case Right(pr) =>
                recordOpenedPr(pr)
                Some(pr)

/** Run one remote-facing leg, turning what it refuses into the line this step
  * reports: `git.push` and `gh.createPr` return a `Left` for the refusals they
  * recognise — which `.orThrow` rethrows — and throw a bare
  * [[OrcaFlowException]] for the rest (auth, network, a rejected ruleset), so
  * both arrive here as exceptions.
  */
private def attempt[T](what: String, next: String)(
    leg: => T
): Either[String, T] =
  try Right(leg)
  catch
    case NonFatal(e: OrcaFlowException) =>
      Left(s"$what (${TextUtil.throwableMessage(e)}), no PR opened — $next")

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

/** Whether this branch carries anything but orca's own bookkeeping against the
  * branch the run started on — the same input, from the same progress header,
  * that the lifecycle's throwaway-branch rule measures against, so the two ask
  * one question. A run with no readable header cannot be measured and gets its
  * PR; the lifecycle never deletes a branch a PR was opened from, so the two
  * cannot strand one between them.
  */
private def runChangedCode(using
    ctx: FlowContext,
    control: FlowControl
): Boolean =
  control.progressStore
    .load()
    .map(_.header.startingBranch)
    .forall(git.branchHasChangesExcludingOrca(_, git.currentBranch()))

/** Report why no PR was opened, as the single line the run shows for the step
  * it didn't take.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
