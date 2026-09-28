# Review and fix loops

`import orca.review.*` brings two review calls into scope. Both run reviewers
against a change, hand whatever they find to the coder's session to fix, and
return the findings that are still open at the end. Each round can also run
format and lint gates and Scala checks alongside the reviewers; these are
described in [Gates and checks](gates-and-checks.md).

## The two calls

### `reviewThenFix`

`reviewThenFix(coderSession, reviewers, task, ...)` runs one review round,
followed by one fix turn if the review found anything. The reviewer findings
are not re-checked after the fix: the fixer's word is taken. The lint gate and
the checks, however, are re-run over the fix, with one more fix turn if they
still fail. Reviewers are picked once, with `ReviewerSelector.agentDriven`.

### `reviewAndFixLoop`

`reviewAndFixLoop(coderSession, reviewers, task, ..., maxFixTurns?)` reviews,
fixes and re-evaluates, until the reviewers come back clean, the fixer reports
no fixes, or `maxFixTurns` fix turns have run. The default is 3, so up to four
rounds.

### Parameters

Both calls share these parameters:

- `coderSession`: the `FlowSession` that applies the fixes
- `reviewers`: the roster, a `List[ReviewerAgent]`, see [Rosters](#rosters)
- `task`: a `Task(Title(...), description)` describing the change, see
  [Planning](planning.md)
- `userRequest`: the user's request, for when the prompt is only a pointer,
  such as an issue number; defaults to the run's prompt
- `diff`: which change set the reviewers see, see
  [What reviewers see](#what-reviewers-see)
- `formatCommands`, `lint`, `checks`: the gates and checks, see
  [Gates and checks](gates-and-checks.md)
- `priorOpenFindings`: findings an earlier review left open; they are shown to
  the reviewers
- `fixInstructions`: the fixer's prompt, see
  [Customising prompts](extending.md#customising-prompts)
- `reviewerSelection` (`reviewAndFixLoop` only): how reviewers are picked each
  round, see [Selecting reviewers per round](#selecting-reviewers-per-round)

The built-in flows use `reviewThenFix` after each task, because the final
review sees that code again anyway. They end with a single `reviewAndFixLoop`
over the whole run, because nothing reviews the code after it. For example:

```scala
stage("Final review"):
  reviewAndFixLoop(
    coderSession = session,
    reviewers = allReviewers(reviewAgent),
    task = Task(Title("The whole planned change"), plan.brief),
    diff = ReviewDiff.WholeRun,
    maxFixTurns = 5
  )
```

## What comes back

Both calls return [`OpenFindings`](../api/data-structures.md#review): every
finding the review left open, each with a reason. A finding stays open when the
fixer declined it or did not mention it, when it was first reported in the
round that hit the cap, or when lint or a check still fails on it. Hand the
result to the PR step, which lists the entries in the PR body, see
[Pull requests](pull-requests.md). A flow can also add entries of its own with
`OpenFinding.custom`, see [Gates and checks](gates-and-checks.md).

## What reviewers see

Each reviewer receives the task and the change set.

- **The task.** The task's title and description, each under its own label,
  plus the user's request. Keeping them apart lets a reviewer report a finding
  against the planner's choice, and not only against the code. A flow with no
  planning stage passes its prompt as the title and an empty description.
- **The change set.** By default, everything the enclosing stage has produced
  since it began, whether or not the agent committed along the way. It is
  re-sampled each round, so later rounds see the fixes. Passing
  `diff = ReviewDiff.WholeRun` widens it to everything since the commit the
  run started from, which is what you want for a stage that follows the
  per-task work; reviewers are then told that the change spans every stage.
  Passing `diff = ReviewDiff.Pinned(text)` sends exactly that text, every
  round: reviewers are not told a base commit, the picker's changed-file list
  is read off the diff text, and a reviewer resumed in a later round is told
  there is no new change set.

Note that a change set larger than 128 KiB is cut down: the reviewer gets as
many whole files as fit, followed by a list naming every other changed file
with its line counts, and reads those itself. A pinned diff is sent as given.

```{note}
`WholeRun` needs the commit the run started from. If the progress log has none
(because the run predates that record), or it was rebased away, the call emits
a step saying so and returns without reviewing.
```

Every finding reaches the fixer unfiltered.

## Rosters

There are three ways to build the `reviewers` list:

- `allReviewers(agent)` gives you every reviewer in the catalog, each as a
  read-only agent built from `agent`. See
  [Custom reviewers](../using/reviewers.md).
- `minimalReviewers(agent)` gives you code-functionality, readability and test,
  plus every reviewer discovered in `.orca/reviewers/` or the global tier.
- `reviewerCatalog` holds the run's resolved definitions, as `.all` and
  `.minimal`, so that you can filter them yourself. Compose a `List[Reviewer]`
  from it, from `ReviewerPrompts` (the shipped entries alone), or from your own
  `Reviewer(ReviewerSlug(name), description, systemPrompt)`, and then pass it
  to `buildReviewers(agent, list)`.

## Selecting reviewers per round

`reviewerSelection` defaults to `ReviewerSelector.default`, which narrows the
roster in two ways. First, a picker running on `reviewAgent`'s
[cheap tier](choosing-agents.md#the-cheap-tier) chooses the reviewers for
round one, based on each reviewer's description and the changed paths. Second,
each later round re-runs only the reviewers that reported a finding in the
round before. In other words, a quiet reviewer stops costing a turn, but it
also does not see the later fixes. If the narrowing would leave no reviewer
while a lint finding keeps the loop going, the round-one selection runs again
and a step says so.

The available selectors are:

- `default`: the same as `narrowingAcrossRounds(agentDriven)`
- `allEveryRound`: the whole roster, every round, with no picker
- `agentDriven`: pick once with `reviewAgent.cheap`, and replay that pick every
  round
- `agentDriven(agent, instructions?)`: as above, with a picker and brief of
  your choice
- `narrowingAcrossRounds(base)`: adds the per-round narrowing on top of any
  `base` selector

Note that a reviewer's `files:` pattern gates whether the picker is offered it
at all, see [Custom reviewers](../using/reviewers.md#file-format).
