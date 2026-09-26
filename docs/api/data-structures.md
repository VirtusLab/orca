# Data structures

The types you meet in flow scripts. Most derive `JsonData`, which makes them
valid stage results (the progress log can record and replay them) and usable as
structured LLM output via `agent.resultAs[T]`. Handles (`FlowSession`, `Chat`)
and intermediate values (`WithChat`, `Verdict`) do not, and cannot be stage
results.

## Planning

- **`orca.plan.Plan(epicId, description, tasks, brief)`** — the task list the
  planner generates in one round trip. `epicId` is a kebab-case identifier for
  the plan, not the branch name. `description` is the planner's epic summary.
  `brief` is the planner's short codebase briefing; use it as the session
  seed: `agent.session("implementer", seed = plan.brief)`.
  `plan.taskPrompt(task)` prepends the brief to a task's description.
- **`orca.plan.Task(title: Title, description: String)`** — `title` is the
  label shown in the event log.
- **`orca.plan.WithChat(chat, value)`** — returned by every `Plan.*` planning
  call ([Planning](../authoring/planning.md)): the result and the `Chat` that
  produced it. You never build one; destructure it:
  `val WithChat(chat, plan) = ...`.
- **`orca.plan.Verdict[A]`** — `Verdict.Proceed(value)` or
  `Verdict.Rejection(kind, body)`, with `kind` one of `Question`, `Critique`,
  `Rebuff`. Returned by `assessThenPlan` as `Verdict[Plan]`, see
  [Verdicts and triage](../authoring/planning.md#verdicts-and-triage).
- **`orca.plan.Triage`** — returned by `triage`: `NotABug`, `Untestable` or
  `Testable`; each case carries its own fields.
- **`orca.plan.BugReportMatch`** — the agent's decision on whether a CI failure
  matches the original report; a structured-output type the bug-fix flow asks
  for.

## Conversations

- **`orca.FlowSession`** — the durable, resumable session handle from
  `agent.session(name, seed)`. `.run(prompt)` or `.resultAs[O].run(input)`
  drives the agent. When the harness no longer holds the conversation, the
  turn is prefixed with the seed and a list of the completed stages.
  `session.chat` is its conversation as an ephemeral `Chat`.
- **`orca.agents.Chat[B]`** — the ephemeral multi-turn handle from
  `agent.chat()`. A chat uses tools and edits the workspace like any agent
  turn; "chat" describes its lifetime, not its powers. It lives for one attempt
  and may be used from a fork. Also carried by `WithChat`.

## Labels and handles

- **`orca.Title`** — a wrapper around `String` for short labels (`Task.title`,
  `ReviewFinding.title`). `Title("…")` constructs, `.value` reads.
- **`orca.tools.PrHandle`** — an open pull request: `host`, `owner`, `repo`,
  `number`. Returned by `gh.createPr`. Build one with
  `PrHandle.from(host, owner, repo, number)`, where a `Left` names the invalid
  field, or `PrHandle.fromUrl(url)`. `host` is `github.com` or a GitHub
  Enterprise hostname, and every `gh` call taking the handle is routed to it.
  Its `JsonData` form is the PR URL, so a push-and-open-PR stage can return it.
- **`orca.tools.IssueHandle`** — a GitHub issue. Carries no host: issue flows
  use gh's default host (`GH_HOST`, else the host gh is logged in to).
- **`orca.tools.GitHubAvailability`** — what `gh.availability` answers.
  `Available(host, owner, repo)` is the repository gh resolves.
  `Unavailable(why)` means no PR can be opened; `why` is a `GitHubUnavailable`:
  - `NoRemote`: no `origin`
  - `NoHost(remote)`: `origin` has no host, a local path
  - `NotGitHub(host)`: gh has no login for the host; a GHES host needs
    `gh auth login --hostname <host>`
  - `Unreachable(host, reason)`: the host is GitHub, but gh could not answer;
    `reason` is gh's own words
  - `GitUnusable(reason)`: git itself could not be run

  `why.explanation` renders it as one line.
- **`orca.pr.PrSummary(title, body)`** — what `summarisePr` returns; feeds
  `gh.createPr(title = …, body = …)` directly.

## Review

- **`orca.review.ReviewFinding`** — one problem a reviewer reported: a `title`
  (shown), a long `description` (sent to the fixer), an optional `location`,
  and `reopens`: the `FindingId` of the still-open finding it reports again, if
  any.
- **`orca.review.ReviewResult(findings)`** — what one reviewer, the lint gate
  or a check returns: a list of findings. `ReviewResult.empty` is a clean
  result.
- **`orca.review.OpenFindings(findings, skipped)`** — what a review loop leaves
  open once it halts. Each `OpenFinding(id, title, reason, location)` carries an
  `OpenReason`:
  - `Declined(text)`: the fixer refused, in its own words
  - `NoFixes`: the fixer reported no fixes at all
  - `Unaccounted`: the fixer never mentioned the finding
  - `CapReached(max)`: first reported in the round that hit `maxFixTurns`
  - `StillFailing(sources)`: lint or a check still fails after the fix turn
  - `Custom(text)`: recorded by the flow with `OpenFinding.custom`

  `reason.describe` is the sentence shown to a reader. Entries merge across
  rounds by `id`, never by title. `skipped` is `Some(SkippedReview)` when the
  review never ran.
- **`orca.review.Lint(commands, agent)`** — the lint gate bundle: the shell
  commands plus the cheap agent that summarises their output into a
  `ReviewResult`.

## Settings

- **`orca.StackSettings(format, lint, test)`** — the resolved per-project
  [gate](../glossary/users.md#review) commands, each a `List[String]` run via
  `bash -c`; empty means the gate is disabled. Read it via
  `summon[FlowContext].stackSettings`; pin it with
  `flow(stackSettings = Some(...))`.
- **`orca.Configured[A]`** — how a review call takes a gate's commands:
  `FromSettings` (the default), `Off`, or `Use(value)`. See
  [Gates and checks](../authoring/gates-and-checks.md).
