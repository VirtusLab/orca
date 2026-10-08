# Data structures

This page lists the types you meet in flow scripts. Most of them derive
`JsonData`, which makes them valid stage results (the progress log can record
and replay them) and lets you ask for them as structured LLM output via
`agent.resultAs[T]`. Handles (`FlowSession`, `Chat`) and `WithChat` do not
derive `JsonData`, so they cannot be stage results.

## Planning

These are the types the planning calls described in
[Planning](../authoring/planning.md) produce.

- **`orca.plan.Plan(id, description, tasks, brief)`** is the task list the
  planner generates in one round trip. `id` is a kebab-case identifier for
  the plan, not the branch name, and `description` is the planner's epic
  summary. `brief` is the planner's short codebase briefing; use it as the
  session seed, as in `agent.session("implementer", seed = plan.brief)`.
  `plan.taskPrompt(task)` prepends the brief to a task's description.
- **`orca.plan.Task(title: Title, description: String)`** is one task of a
  plan. Its `title` is the label shown in the event log.
- **`orca.plan.WithChat(chat, value)`** is returned by every `Plan.*` planning
  call: the result together with the `Chat` that produced it. You never build
  one yourself; destructure it instead: `val WithChat(chat, plan) = ...`.
- **`orca.plan.Roadmap(description, epics, brief)`** is what `roadmap`
  returns: a change too large for one plan, split into ordered epics. `brief`
  is shared by every epic's planner and implementers.
  `roadmap.epicPrompt(epic)` is the planning input for one epic.
- **`orca.plan.Epic(title: Title, goal: String)`** is one part of a roadmap,
  planned into tasks only when its turn comes.
- **`orca.plan.Triage`** is what `triage` returns: `Reject(reply)` or
  `Accept(summary, brief, kind)`, where `kind` is `TestableBug(failingTestPath)`,
  `UntestableBug(reproductionSteps)` or `Change`; see
  [Triage](../authoring/planning.md#triage).
- **`orca.plan.BugReportMatch(matches, explanation)`** is the agent's decision
  on whether a failing test's output matches the original report. It is a
  structured-output type `reproduceBug` asks for.

## Conversations

Two handles represent a conversation with an agent.

- **`orca.FlowSession`** is the durable, resumable session handle you get from
  `agent.session(name, seed)`. You drive the agent with `.run(prompt)` or
  `.resultAs[O].run(input)`. When the harness no longer holds the
  conversation, the turn is prefixed with the seed and a list of the completed
  stages. `session.chat` exposes the session's conversation as an ephemeral
  `Chat`.
- **`orca.agents.Chat[B]`** is the ephemeral multi-turn handle you get from
  `agent.chat()`. A chat uses tools and edits the workspace like any agent
  turn; "chat" describes its lifetime, not its powers. It lives for one attempt
  and may be used from a fork. `WithChat` also carries one.

## Labels and handles

- **`orca.Title`** is a wrapper around `String` for short labels, such as
  `Task.title` and `ReviewFinding.title`. `Title("…")` constructs one and
  `.value` reads it.
- **`orca.tools.PrHandle`** identifies an open pull request by `host`,
  `owner`, `repo` and `number`. `gh.createPr` returns one. You can also build
  one with `PrHandle.from(host, owner, repo, number)`, where a `Left` names the
  invalid field, or with `PrHandle.fromUrl(url)`. `host` is `github.com` or a
  GitHub Enterprise hostname, and every `gh` call taking the handle is routed
  to it. Its `JsonData` form is the PR URL, so a push-and-open-PR stage can
  return it.
- **`orca.tools.IssueHandle`** identifies a GitHub issue. It carries no host:
  `gh` calls taking it use gh's default host, which is `GH_HOST` if set, else
  the host gh is logged in to. `IssueHandle.parse` reads `owner/repo#n` or a
  github.com issue or PR URL; `IssueHandle.parseIssue` rejects the PR URL.
- **`orca.tools.GitHubAvailability`** is what `gh.availability` answers.
  `Available(host, owner, repo)` is the repository gh resolves.
  `Unavailable(why)` means no PR can be opened, and `why` is a
  `GitHubUnavailable`, one of:
  - `NoRemote`: there is no `origin`
  - `NoHost(remote)`: `origin` has no host, because it is a local path
  - `NotGitHub(host)`: gh has no login for the host; a GHES host needs
    `gh auth login --hostname <host>`
  - `Unreachable(host, reason)`: the host is GitHub, but gh could not answer;
    `reason` is gh's own words
  - `GitUnusable(reason)`: git itself could not be run

  `why.explanation` renders the reason as one line.
- **`orca.pr.PrSummary(title, body)`** is what `summarisePr` returns. It feeds
  `gh.createPr(title = …, body = …)` directly.

## Review

- **`orca.review.ReviewFinding`** is one problem a reviewer reported. It has a
  `title`, which is shown, a long `description`, which is sent to the fixer, an
  optional `location`, and `reopens`: the `FindingId` of the still-open finding
  it reports again, if any.
- **`orca.review.ReviewResult(findings)`** is what one reviewer, the lint gate
  or a check returns: a list of findings. `ReviewResult.empty` is a clean
  result.
- **`orca.review.OpenFindings(findings, skipped)`** is what a review loop
  leaves open once it halts. Each `OpenFinding(id, title, reason, location)`
  carries an `OpenReason`, one of:
  - `Declined(text)`: the fixer refused, in its own words
  - `NoFixes`: the fixer reported no fixes at all
  - `Unaccounted`: the fixer never mentioned the finding
  - `CapReached(max)`: the finding was first reported in the round that hit
    `maxFixTurns`
  - `StillFailing(sources)`: lint or a check still fails after the fix turn
  - `Custom(text)`: recorded by the flow with `OpenFinding.custom`

  `reason.describe` is the sentence shown to a reader. Entries merge across
  rounds by `id`, never by title. `skipped` is `Some(SkippedReview)` when the
  review never ran.
- **`orca.review.ReviewTarget(summary, diffPath, changedFiles)`** is what
  `reviewOnce` reviews: a one-line summary, the repo-relative path of a file
  holding the unified diff, and the changed files.
- **`orca.review.ReviewReport(target, byReviewer)`** is what `reviewOnce`
  returns: each reviewer's findings as a `ReviewerFindings(reviewer, findings)`.
  `report.render` is the report as markdown, fit to print or post on a PR.
- **`orca.review.Lint(commands, agent)`** is the lint gate bundle: the shell
  commands plus the cheap agent that summarises their output into a
  `ReviewResult`.

## Settings

- **`orca.StackSettings(format, lint, test)`** holds the resolved per-project
  [gate](../glossary/users.md#review) commands. Each is a `List[String]` run
  via `bash -c`, and an empty list means the gate is disabled. Read it via
  `summon[FlowContext].stackSettings`, or pin it with
  `flow(stackSettings = Some(...))`.
- **`orca.Configured[A]`** is how a review call takes a gate's commands:
  `FromSettings` (the default), `Off`, or `Use(value)`. See
  [Gates and checks](../authoring/gates-and-checks.md).
