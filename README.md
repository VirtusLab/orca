# Orca

Deterministic, AI-driven development flows.

Orca allows you to programmatically define software development workflows where
AI agents perform the coding. If you want AI-generated code to always be
reviewed by another agent, don’t try to coerce the agents; just express that
requirement in code. Don’t waste tokens on formatting, committing, or creating
PRs - all of this can be handled by an ordinary script.

Orca flow scripts are written in Scala, and can be run with a single command
through [scala-cli](https://scala-cli.virtuslab.org). No other dependencies need
to be installed - everything is automatically bootstrapped. Scala 3 looks like
Python, but with types - so you get quick feedback if your flow script has any
problems.

Orca assumes that it has configured, logged-in access to Claude, Codex
(depending which provider you use), as well as gh and git.

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

import orca.{*, given}

flow(OrcaArgs(args)):
  // Plan persists to `.orca/plan-<hash>.md` so a re-run with the same
  // prompt resumes from the first incomplete task. Plan.autonomous.from
  // runs the planner as a single agentic turn (use Plan.interactive.from
  // to let the planner ask clarifying questions). `recoverOrCreate`
  // ensures the branch is checked out and the file is on disk before we
  // start implementing.
  val planFile = Plan.defaultPath(userPrompt)
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, "orca: starting work"):
      Plan.autonomous.from(userPrompt, claude)

  // Stable session reused across every task so the implementer retains
  // cross-task context. The planner's session isn't carried forward — it
  // runs read-only and would inherit the restriction on resume.
  val session = claude.newSession

  // Per task: implement, format, review & fix. `implementTaskLoop` ticks
  // the plan's checkbox + commits per task and removes the plan file at
  // the end. The single commit captures the original implementation, the
  // auto-formatted result, and any follow-up fixes the reviewers triggered.
  Plan.implementTaskLoop(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        val _ = claude.autonomous.run(task.description, session)
      // Format before review so reviewers don't burn turns on style nits
      // the toolchain would fix automatically. Run after the agent
      // writes — we don't want to demand pre-formatted code from the LLM.
      stage("Format"):
        val _ = os.proc("sbt", "scalafmtAll").call(check = false)
      reviewAndFixLoop(
        coder = claude,
        sessionId = session,
        reviewers = allReviewers(claude),
        // Cheap model picks which reviewers run per task — sees each
        // one's description plus the changed files. Swap for
        // ReviewerSelector.allEveryRound to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value,
        lintCommand = Some("sbt test"),
        lintLlm = Some(claude.haiku)
      )
```

```bash
scala-cli run implement.sc -- "Add a rate-limiter to the /login endpoint"
```

There are four runnable examples which you migh try:
* [01-simple](examples/01-simple/) (in-memory plan + review, autonomous planner),
* [02-interactive](examples/02-interactive/) (same shape as 01, but the planner
  can ask clarifying questions via `ask_user`),
* [03-bugfix](examples/03-bugfix/) (red-test-first against a real PR),
* [04-epic](examples/04-epic/) (resumable disk-backed plan with cross-agent
  review).

For convenient editing of Orca flow scripts, with code-completion, you can try
the [Metals](https://scalameta.org/metals/) VSCode extension.

## Built-in tools

The following are available inside a `flow(...) { ... }`:

| Tool | Methods | Purpose |
|---|---|---|
| `claude` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `haiku`/`sonnet`/`opus`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly` | Claude Code coding/reviewing agent. Each `run` returns `(SessionId, output)`. Pre-allocate a session with `claude.newSession` and pass it on every call to keep one conversation alive; omit the arg for a one-shot fresh session. The `autonomous` vs `interactive` mode is always visible at the call site (interactive lives only on `resultAs[O]`). |
| `codex` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `mini`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly` | OpenAI Codex coding/reviewing agent. |
| `git` | `createBranch`, `checkout`, `checkoutOrCreate`, `ensureClean`, `commit`, `push`, `currentBranch`, `diff`, `log`, `addWorktree`, `removeWorktree`, `listWorktrees` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushRejected`, `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. |
| `gh` | `createPr`, `readIssue`, `readIssueComments`, `readPrComments`, `writeComment(pr, body)` / `writeComment(issue, body)`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `createPr` returns `Either[PrCreateFailed, …]` (covers `PrAlreadyExists` / `NoCommitsToPr`); `waitForBuild` returns `Either[BuildTimedOut, …]`. |
| `fs` | `read`, `write`, `list` | Working-tree file I/O. `read` returns `Option[String]` so a missing file is a branch point, not an exception. |

For the LLM interfaces, `resultAs[O]` defines the shape of the structured
output. The `O` type needs a `JsonData[O]` (provided by `derives JsonData` on a
case class) for schema generation and deserialization. Additionally, you might
define an `Announce[O]` so that a friendly summary is printed in the event log,
instead of a raw json.

For multi-task loops, pre-allocate one session and pass it on every call:

```scala
val session = claude.newSession
for task <- tasks do
  val _ = claude.autonomous.run(task.description, session)
```

The first call opens the session; subsequent calls resume it. The library
tracks fresh-vs-resume internally per backend (`--session-id` then `--resume`
on claude; a client→server mapping on codex).

## Flow methods

Top-level, available via `import orca.*`:

| Method | Use |
|---|---|
| `flow(args, ...)(body)` | Entry point. Sets up the `FlowContext` for the body. |
| `stage(name)(body)` | Wrap an operation in a named stage. Emits `StageStarted`/`StageCompleted` and shows in the status-bar breadcrumb. |
| `fail(message)` | Abort the current stage with an error. |

Planning utilities, available via `import orca.plan.*`:

| Method | Use |
|---|---|
| `Plan.interactive.from(userPrompt, llm, instructions?)` | Open an interactive planning round-trip — the agent can ask clarifying questions before producing the plan. Returns just the `Plan`; the implementer allocates its own session via `llm.newSession`. |
| `Plan.autonomous.from(userPrompt, llm, instructions?)` | Same shape as `interactive.from` but the planning runs as a single agentic turn, no human in the loop. Runs read-only — the planner can read/grep but can't edit. |
| `Plan.autonomous.assessThenPlan(userPrompt, llm, instructions?)` | Skeptically assess `userPrompt` (typically a bug/feature report) against the repo and either return a plan (`Verdict.Proceed`) or a critique/follow-up question/rebuff the caller surfaces back to the reporter (`Verdict.Rejection`). Used by `issue-pr.sc`. |
| `Plan.interactive.loadOrGenerate(file, userPrompt, llm, instructions?)` | Idempotent plan acquisition with interactive generation: parse `file` if it exists (resume), otherwise drive the planner conversationally and persist the result as markdown. |
| `Plan.autonomous.loadOrGenerate(file, userPrompt, llm, instructions?)` | Same, but generation is autonomous. |
| `Plan.defaultPath(userPrompt, workDir?)` | Returns `<workDir>/.orca/plan-<hash>.md` — the conventional persistent-plan path. `<hash>` is the first 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars, so unrelated prompts in the same repo don't collide. |
| `Plan.recover(file)` | Resume-from-crash entry point. If `file` exists: stash pending edits (`git stash pop` recovers them), switch to the plan's branch, parse the file, return `Some(plan)`. Otherwise `None` so the caller can fall back to generating. |
| `Plan.implementTaskLoop(file, plan)(body)` | Iterate `plan` running `body(task)` per task; tick the checkbox + commit per task; remove `file` and commit the cleanup at the end. Body owns the per-task work (implement, review, lint). |
| `Plan.persistComplete(file, title)` | Mark one task complete on disk. Lower-level primitive that `implementTaskLoop` is built on. |

Picking interactive vs autonomous is visible at the call site rather than
hidden behind a parameter default — `Plan.interactive.*` and `Plan.autonomous.*`
are sibling namespaces with the same method shapes.

Persistent plans are the default mode for multi-task flows — `implement.sc`,
`implement-interactive.sc`, and `epic.sc` all use `Plan.defaultPath` +
`Plan.recover` + `Plan.implementTaskLoop`. See ADR
[0013](adr/0013-persistent-plans.md) for the convention and migration notes.

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(command, llm, instructions?)` | Run a shell lint, hand the output to `llm`, parse as `ReviewResult`. |
| `reviewAndFixLoop(coder, sessionId, reviewers, task, ..., fixInstructions?)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to `coder` to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. |
| `allReviewers(base)` | All seven canonical reviewer agents (performance, readability, test, code-functionality, abstraction, backend-architect, scala-fp) layered on top of `base`. |
| `minimalReviewers(base)` | Universally-applicable subset (code-functionality, readability, test). Pair with the default LLM-driven selector when the full set is overkill. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop` requires a `reviewerSelection: ReviewerSelector` argument.
Typically `ReviewerSelector.llmDriven(claude.haiku)` — the picker LLM (use a
cheap model) sees each reviewer's description plus the changed file paths and
narrows the supplied list per task. Pass
`ReviewerSelector.allEveryRound` to run every reviewer every iteration, or
`ReviewerSelector.onlyPreviouslyReporting` to re-run only the reviewers that
found something last round.

PR utilities, available via `import orca.pr.*`:

| Method | Use |
|---|---|
| `summarisePr(llm, diff, context?, instructions?)` | Fold a branch diff into a `PrSummary(title, body)` for `gh.createPr`. `context` is an optional preamble (originating issue link, user prompt, etc.) the model anchors the description to. Use a cheap model (`claude.haiku`, `codex.mini`). |

### Customising prompts

Every domain helper that bundles an LLM brief takes the prompt as a
default-valued `instructions: String` parameter; the default value lives on a
sibling `XxxPrompts` object in the same package. Override by passing a
different string, or compose with the default to extend it:

```scala
import orca.plan.{Plan, PlanPrompts}

Plan.interactive.from(
  userPrompt,
  claude,
  instructions = PlanPrompts.Planning + "\n\nPrioritise observability tasks first."
)
```

Where the defaults live:
- `orca.plan.PlanPrompts` — `Planning`
- `orca.pr.PrPrompts` — `Summarise`
- `orca.review.ReviewLoopPrompts` — `Fix`, `SelectReviewers`, `SummarizeLint`
- `orca.review.ReviewerPrompts` — per-reviewer system prompts (compose your own
  list to swap or extend `allReviewers`/`minimalReviewers`)

The lower-level per-call wrappers (autonomous/interactive/retry) are a
separate layer — replace the whole set via `flow(prompts = ...)`. See ADR
[0010](adr/0010-prompts-and-helpers-convention.md) for the full convention.

## Data structures

Common types you'll see in flow scripts. All `derives JsonData`, so the agent
can generate them as structured output via `claude.resultAs[T]`:

- **`orca.plan.Plan(epicId, tasks)`** — list of tasks the agent generates in
  one round-trip; the same type backs both in-memory use (`Plan.interactive.from` /
  `Plan.autonomous.from`) and the markdown-persisted resume path
  (`Plan.interactive.loadOrGenerate` / `Plan.autonomous.loadOrGenerate`).
  `epicId` is a
  kebab-case identifier used as the git branch name for the whole plan.
- **`orca.plan.Task(title, description, completed?)`** — `title` is the
  human-readable label shown in the event log and used as the
  `## Task: <title>` markdown header when persisted.
- **`orca.Title`** — opaque alias of `String` shared by `Task.title` and
  `ReviewIssue.title`. Construct via `Title("…")`; recover the string with
  `.value`. Keeps short labels from being silently swapped with descriptions
  or raw user input.
- **`orca.bug.BugTriage`** / **`orca.bug.BugReportMatch`** — the agent's
  decision on whether a bug can be reproduced as a unit test, and whether a CI
  failure matches the report.
- **`orca.pr.PrSummary(title, body)`** — what `summarisePr` returns. The two
  fields feed `gh.createPr(title = …, body = …)` directly.
- **`orca.review.ReviewIssue` / `ReviewResult`** — what reviewer agents return.
  Issues carry severity, confidence, a `title` (shown), and a long
  `description` (sent to the fixer).
- **`orca.review.FixOutcome(fixed, ignored)`** — what the fix step returns:
  the titles of issues actually fixed in code, plus titles + reasons for
  issues set aside (environmental, out of scope, false positive). The loop
  re-evaluates iff `fixed` is non-empty.
- **`orca.review.IgnoredIssues`** — accumulated `IgnoredIssue(title, reason)`
  entries surfaced by `reviewAndFixLoop` once it halts.

## Output

While Orca runs the terminal output is split into two zones: an **event log**
that grows top-to-bottom as stages and tools fire, and a **status line** pinned
to the bottom, showing the active stage breadcrumb with a spinner. Nested stages
are indented.

| Glyph | Meaning |
| ----- | ------- |
| `▶` | Stage start, or a `Step` (single-line note like a branch switch) |
| `▸` | User's prompt at the start of an interactive session |
| `●` | Assistant prose |
| `⏺` | Tool call (path / command / query in grey) |
| `⎿` | Tool result (truncated to one line) |
| `✖` | Error |
| `?` | Approval request |

Colours and animation auto-disable when stderr isn't a terminal. Set
`NO_COLOR=1` or `ORCA_NO_ANIMATION=1` (suppresses the spinner) to force them
off.

## Authenticating the coding agents

Each CLI handles its own auth; Orca itself stores no secrets.

## Getting set up

Orca is published to Maven Central — `scala-cli` fetches the artifacts on first
run:

```bash
scala-cli run implement.sc -- "your task here"
```

## Documentation

- [`design.md`](design.md) — architecture and design rationale.
- [`adr/`](adr/) — architecture decision records.
- [`AGENTS.md`](AGENTS.md) — internals, conventions, build/test recipes; the
  same file AI assistants pick up.

## License

Not yet set.
