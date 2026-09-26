# Review and fix loops

`import orca.review.*` gives you two review calls. Both run reviewers against a
change, hand the findings to the coder's session to fix, and return what stays
open. The format and lint gates and Scala checks that run with them are
described in [Gates and checks](gates-and-checks.md).

| Call | Does |
|---|---|
| `reviewThenFix(coderSession, reviewers, task, ...)` | One review round, then one fix turn if it found anything. Reviewer findings are not re-checked: the fixer's word is taken. The lint gate and checks are re-run over the fix, with one more fix turn if they still fail. Reviewers are picked once, with `ReviewerSelector.agentDriven`. |
| `reviewAndFixLoop(coderSession, reviewers, task, ..., maxFixTurns?)` | Review, fix, re-evaluate, until reviewers come back clean, the fixer reports no fixes, or `maxFixTurns` fix turns have run (default 3, so up to four rounds). |

Parameters both calls share:

| Parameter | Meaning |
|---|---|
| `coderSession` | the `FlowSession` that fixes |
| `reviewers` | the roster, a `List[ReviewerAgent]`, see [Rosters](#rosters) |
| `task` | a `Task(Title(...), description)` describing the change, see [Planning](planning.md) |
| `userRequest` | the user's request when the prompt is only a pointer, such as an issue number; defaults to the run's prompt |
| `diff` | which change set reviewers see, see below |
| `formatCommands`, `lint`, `checks` | the gates and checks, see [Gates and checks](gates-and-checks.md) |
| `priorOpenFindings` | findings an earlier review left open, shown to the reviewers |
| `fixInstructions` | the fixer's prompt, see [Customising prompts](extending.md#customising-prompts) |
| `reviewerSelection` (`reviewAndFixLoop` only) | how reviewers are picked each round, see below |

The built-in flows use `reviewThenFix` per task, because the final review sees
that code again. They end with one `reviewAndFixLoop` over the whole run,
because nothing reviews after it:

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
fixer declined it, did not mention it, when it was first reported in the round
that hit the cap, or when lint or a check still fails on it. Hand the result to
the PR step, which lists them in the PR body, see
[Pull requests](pull-requests.md). A flow can add its own entries with
`OpenFinding.custom`, see [Gates and checks](gates-and-checks.md).

## What reviewers see

- **The task.** The task's title and description, each under its own label,
  plus the user's request. Keeping them apart lets a reviewer report a finding
  against the planner's choice, not only the code. A flow with no planning
  stage passes its prompt as the title and an empty description.
- **The change set.** By default everything the enclosing stage has produced
  since it began, whether or not the agent committed along the way. It is
  re-sampled each round, so later rounds see the fixes.
  `diff = ReviewDiff.WholeRun` widens it to everything since the commit the run
  started from, for a stage after the per-task work; reviewers are told the
  change spans every stage. `diff = ReviewDiff.Pinned(text)` sends exactly that
  text, every round: reviewers are not told a base commit, the picker's
  changed-file list is read off the diff text, and a reviewer resumed in a
  later round is told there is no new change set.
- A change set past 128 KiB is cut down: the reviewer gets as many whole files
  as fit, then a list naming every other changed file with its line counts, and
  reads those itself. A pinned diff is sent as given.

`WholeRun` needs the commit the run started from. If the progress log has none
(the run predates that record) or it was rebased away, the call emits a step
saying so and returns without reviewing.

Every finding reaches the fixer unfiltered.

## Rosters

- `allReviewers(agent)`: every reviewer in the catalog, each as a read-only
  agent built from `agent`. See [Custom reviewers](../using/reviewers.md).
- `minimalReviewers(agent)`: code-functionality, readability and test, plus
  every reviewer discovered in `.orca/reviewers/` or the global tier.
- `reviewerCatalog`: the run's resolved definitions, `.all` and `.minimal`, to
  filter yourself. Compose a `List[Reviewer]` from it, from `ReviewerPrompts`
  (the shipped entries alone), or your own
  `Reviewer(ReviewerSlug(name), description, systemPrompt)`, then
  `buildReviewers(agent, list)`.

## Selecting reviewers per round

`reviewerSelection` defaults to `ReviewerSelector.default`. It narrows the
roster in two ways. First, a picker on `reviewAgent`'s
[cheap tier](choosing-agents.md#the-cheap-tier) chooses reviewers for round one
from each reviewer's description and the changed paths. Second, each later round
re-runs only the reviewers that reported a finding in the round before. A quiet
reviewer stops costing a turn, but it does not see later fixes. If narrowing
would leave no reviewer while a lint finding keeps the loop going, the
round-one selection runs again and a step says so.

| Selector | Behaviour |
|---|---|
| `default` | `narrowingAcrossRounds(agentDriven)` |
| `allEveryRound` | the whole roster, every round; no picker |
| `agentDriven` | pick once with `reviewAgent.cheap`, replay that pick every round |
| `agentDriven(agent, instructions?)` | as above with a chosen picker and brief |
| `narrowingAcrossRounds(base)` | adds the per-round narrowing over any `base` |

A reviewer's `files:` pattern gates whether the picker is offered it, see
[Custom reviewers](../using/reviewers.md#file-format).
