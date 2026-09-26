# Pull requests

`import orca.pr.*` gives you the PR helpers. They build on the `gh` tool
described in [Git, GitHub and file tools](../api/tools.md).

## Opening a PR

| Call | Does |
|---|---|
| `openPrIfGitHub(summarisingAgent, openFindings, title?, body?, context?, instructions?): Option[PrHandle]` | Pushes, summarises and opens the PR when `gh.availability` says the checkout is on a reachable GitHub. Otherwise it emits one step saying why and returns `None`; the run then finishes. This is how every code-producing built-in flow ends. |
| `openPrFromBranch(summarisingAgent, openFindings, title?, body?, context?, instructions?): PrHandle` | The same three stages, but fails the run when there is no GitHub remote or `gh` login. |
| `summarisePr(agent, diff, context?, instructions?): PrSummary` | Turns a branch diff into a [`PrSummary(title, body)`](../api/data-structures.md#labels-and-handles) for `gh.createPr`. `context` is an optional preamble the model anchors the description to. An oversized diff is truncated. Use a [cheap](choosing-agents.md#the-cheap-tier) model. |

`None` cases of `openPrIfGitHub`: no remote, a remote that is not GitHub, a
GitHub host `gh` cannot reach, a run that changed no code, or a push or create
the remote refused. The probe is skipped on a resume that replays the push
stage; a resume replays what its push and create stages recorded, a refusal
included.

Both open calls create stages, so they cannot be called inside a `stage(...)`
body. Call them at the top level of the flow.

`openFindings` is the final review's [`OpenFindings`](review.md#what-comes-back).
The PR body lists each entry under "Open review findings": title, location if
any, and reason. If the review was skipped, a line says so. The same section
goes to the run output, also when the PR fails.

`title` and `body` are functions `PrSummary => String` that rewrite the
generated text. `context` defaults to the run's prompt; with that default the
summariser adds `Closes #N` for each issue the prompt names. If you pass your
own `context`, add the `Closes` line yourself through `body`:

```scala
val issue = gh.readIssue(handle)   // handle: an IssueHandle

openPrIfGitHub(
  summarisingAgent = codingAgent.cheap,
  openFindings = openFindings,
  context = Some(issue.body),
  body = s => s"${s.body}\n\nCloses #42."
)
```

After a PR is opened, the run returns your checkout to the branch you started
on, see [Branches, resume and worktrees](../using/run-lifecycle.md).

## Writing your own PR body

For a flow that opens or updates its PR with bare `gh` calls:

| Call | Does |
|---|---|
| `bodyWithOpenFindings(body, open)` | `body` with the "Open review findings" section appended, or unchanged when nothing is open and the review ran |
| `reportOpenFindings(open)` | prints that section to the run output; call it before your PR step |
| `recordOpenedPr(pr)` | records the PR URL as the run's published work, so the checkout returns to the starting branch and the closing summary names the PR. Call it inside the stage that opened the PR, so the stage's commit carries the record for resume |

The `gh` writes are idempotent, so a stage that opens a PR or posts a comment
is safe to re-run after a crash. See the
[authoring rules](stages.md#authoring-rules) and [`gh`](../api/tools.md#gh).
