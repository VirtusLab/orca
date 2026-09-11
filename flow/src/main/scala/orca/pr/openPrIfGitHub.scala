package orca.pr

import orca.{FlowContext, FlowControl, gh}
import orca.agents.Agent
import orca.events.OrcaEvent
import orca.tools.{GitHubAvailability, PrHandle}

/** [[openPrFromBranch]] where a PR can be opened, and nothing but one reported
  * line where it can't — for a flow that should finish its work either way.
  * Returns the handle when a PR was opened, `None` when the checkout has no
  * GitHub to open it against.
  *
  * The probe (`gh.availability()`) runs outside any stage: it only reads, and a
  * run that skips the PR must not leave a recorded stage that a resume would
  * replay as "done".
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
    case GitHubAvailability.Available(_, _, _) =>
      Some(
        openPrFromBranch(
          summarisingAgent = summarisingAgent,
          title = title,
          body = body,
          context = context,
          instructions = instructions
        )
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
    // gh's reason ends in the command to run, so it goes last.
    case GitHubAvailability.Unreachable(host, reason) =>
      skipped(s"cannot reach GitHub ($host), no PR opened — $reason")

/** Report why no PR was opened, as the single line the run shows for the step
  * it didn't take.
  */
private def skipped(message: String)(using ctx: FlowContext): None.type =
  ctx.emit(OrcaEvent.Step(message))
  None
