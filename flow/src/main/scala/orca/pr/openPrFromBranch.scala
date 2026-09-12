package orca.pr

import orca.{FlowContext, FlowControl, gh, git, stage}
import orca.agents.Agent
import orca.review.IgnoredIssues
import orca.tools.PrHandle

import ox.either.orThrow

/** Push the current feature branch and open a PR for it, as three separate
  * [[stage]]s: push → summarise → create. Split because a stage commits only on
  * completion, so pushing in the same stage as the summarise (or the preceding
  * edits) would be fragile on resume.
  *
  * The diff handed to the summariser is the branch-vs-base diff
  * (`git.diffVsBase(git.defaultBase())`). A branch too large to summarise is
  * cut short by [[summarisePr]].
  *
  * Customise the PR text with `title`/`body`, both given the generated
  * [[PrSummary]]; a flow that closes an issue passes e.g. `body = s =>
  * s"${s.body}\n\nCloses #42."`. Point `summarisingAgent` at a cheap model and
  * pass `context` to anchor it to the originating issue/prompt.
  *
  * `openFindings` is what the run's review loop returned still open; it goes
  * into the body after `body`'s text as its own section
  * ([[renderOpenFindings]]), never through the summariser. Required, not
  * defaulted: a flow that forgets it would open a PR that says nothing about
  * findings it left unfixed.
  *
  * `gh.createPr` is idempotent by head branch: a re-run that already opened the
  * PR gets the existing handle back rather than failing. Returns that handle.
  */
def openPrFromBranch(
    summarisingAgent: Agent[?],
    openFindings: IgnoredIssues,
    title: PrSummary => String = _.title,
    body: PrSummary => String = _.body,
    context: Option[String] = None,
    instructions: String = PrPrompts.Summarise
)(using FlowContext, FlowControl): PrHandle =
  stage("Push branch"):
    git.push().orThrow

  val summary = stage("Generate PR title and description"):
    summarisePr(
      agent = summarisingAgent,
      diff = git.diffVsBase(git.defaultBase()),
      context = context,
      instructions = instructions
    )

  stage("Open PR"):
    val prBody =
      (body(summary) :: renderOpenFindings(openFindings).toList)
        .mkString("\n\n")
    gh.createPr(title = title(summary), body = prBody).orThrow
