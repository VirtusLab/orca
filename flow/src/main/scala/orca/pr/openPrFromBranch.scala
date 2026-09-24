package orca.pr

import orca.{FlowControl, OutsideStage, fail, gh, git, tracedStage}
import orca.agents.Agent
import orca.review.OpenFindings
import orca.tools.PrHandle

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
  * Refused inside a stage — at compile time where the call sits in a stage
  * body, at run time where it is reached through a helper: opening the PR is a
  * top-level step of a flow, and this runs its own stages.
  */
def openPrFromBranch(
    summarisingAgent: Agent[?],
    openFindings: OpenFindings,
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using FlowControl, OutsideStage): PrHandle =
  summon[FlowControl].assertAtFlowBody("openPrFromBranch(...)")
  reportOpenFindings(openFindings)
  // A refusal throws inside its stage, so it is never recorded and a resume
  // retries it. A recorded `Refused` is one [[openPrIfGitHub]] wrote.
  val push = tracedStage(PushStage):
    git.push().orThrow
    PushResult.Pushed
  push.value.outcome.fold(reason => fail(refusalLine(reason, push)), identity)
  val summary = summarise(
    summarisingAgent,
    git.defaultBase(),
    context,
    instructions
  ).orThrow
  val create = tracedStage(CreateStage):
    val pr = gh
      .createPr(
        title = title(summary),
        body = bodyWithOpenFindings(body(summary), openFindings)
      )
      .orThrow
    recordOpened(pr)
  create.value.outcome
    .fold(reason => fail(refusalLine(reason, create)), identity)
