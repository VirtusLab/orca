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

Save this as `implement.sc` and run it with your task (until Orca is published,
you might need to publish the library locally first, see below):

```scala
//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.{*, given}
import orca.plan.Plan
import orca.review.{defaultReviewers, reviewAndFixLoop}
import ox.either.orThrow

flow(OrcaArgs(args)):
  // Plan.interactive.from opens a conversation the user can drive
  // (clarifying questions, refinements). Swap for Plan.autonomous.from
  // for a single agentic turn, no human in the loop. Both wrap the user
  // prompt with PlanPrompts.Planning so the agent stays in plan-only
  // mode and return the session id so the implementer turns below run
  // on the same context.
  val (sessionId, plan) = stage("Creating a development plan"):
    Plan.interactive.from(userPrompt, claude)

  // Single branch for the whole epic; tasks become commits on it.
  // git.createBranch returns Either[BranchAlreadyExists, Unit]; .orThrow
  // turns the recoverable Left into the same exception we'd surface for a
  // genuinely unexpected git failure, which is what we want here.
  git.createBranch(plan.epicId).orThrow

  for task <- plan.tasks do
    stage(s"Implement task: ${task.title}"):
      stage("Implementation"):
        claude.continueSession(sessionId, task.description)
      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.title.value,
        lintCommand = Some("sbt scalafmtCheckAll test")
      )
      git.commit(s"Implement ${task.title}").orThrow
```

```bash
scala-cli run implement.sc -- "Add a rate-limiter to the /login endpoint"
```

There are three runnable examples which you migh try:
* [01-simple](examples/01-simple/) (in-memory plan + review),
* [02-bugfix](examples/02-bugfix/) (red-test-first against a real PR),
* [03-epic](examples/03-epic/) (resumable disk-backed plan with cross-agent
  review).

For convenient editing of Orca flow scripts, with code-completion, you can try
the [Metals](https://scalameta.org/metals/) VSCode extension.

## Built-in tools

The following are available inside a `flow(...) { ... }`:

| Tool | Methods | Purpose |
|---|---|---|
| `claude` | `autonomous.{run,startSession,continueSession}`, `resultAs[O].{autonomous,interactive}.{run,startSession,continueSession}`, `haiku`/`sonnet`/`opus`, `withConfig`, `withSystemPrompt`, `withName` | Claude Code coding/reviewing agent. The `autonomous` vs `interactive` mode is always visible at the call site (interactive lives only on `resultAs[O]`). |
| `codex` | `autonomous.{run,startSession,continueSession}`, `resultAs[O].{autonomous,interactive}.{run,startSession,continueSession}`, `mini`, `withConfig`, `withSystemPrompt`, `withName` | OpenAI Codex coding/reviewing agent. |
| `git` | `createBranch`, `checkout`, `checkoutOrCreate`, `ensureClean`, `commit`, `push`, `currentBranch`, `diff`, `log`, `addWorktree`, `removeWorktree`, `listWorktrees` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushRejected`, `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. |
| `gh` | `createPr`, `readComments`, `writeComment`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `createPr` returns `Either[PrCreateFailed, …]` (covers `PrAlreadyExists` / `NoCommitsToPr`); `waitForBuild` returns `Either[BuildTimedOut, …]`. |
| `fs` | `read`, `write`, `list` | Working-tree file I/O. `read` returns `Option[String]` so a missing file is a branch point, not an exception. |

For the LLM interfaces, `resultAs[O]` defines the shape of the structured
output. The `O` type needs a `JsonData[O]` (provided by `derives JsonData` on a
case class) for schema generation and deserialization. Additionally, you might
define an `Announce[O]` so that a friendly summary is printed in the event log,
instead of a raw json.

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
| `Plan.interactive.from(userPrompt, llm, instructions?)` | Open an interactive planning round-trip — the agent can ask clarifying questions before producing the plan. Returns the session id + a `Plan` so the caller can `continueSession` on the same context when implementing each task. |
| `Plan.autonomous.from(userPrompt, llm, instructions?)` | Same shape as `interactive.from` but the planning runs as a single agentic turn, no human in the loop. |
| `Plan.interactive.loadOrGenerate(file, userPrompt, llm, instructions?)` | Idempotent plan acquisition with interactive generation: parse `file` if it exists (resume), otherwise drive the planner conversationally and persist the result as markdown. |
| `Plan.autonomous.loadOrGenerate(file, userPrompt, llm, instructions?)` | Same, but generation is autonomous. |
| `Plan.persistComplete(file, title)` | Mark one task complete on disk. |

Picking interactive vs autonomous is visible at the call site rather than
hidden behind a parameter default — `Plan.interactive.*` and `Plan.autonomous.*`
are sibling namespaces with the same method shapes.

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(command, llm, instructions?)` | Run a shell lint, hand the output to `llm`, parse as `ReviewResult`. |
| `reviewAndFixLoop(coder, sessionId, reviewers, task, ..., fixInstructions?)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to `coder` to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. |
| `defaultReviewers(base)` | Five canonical reviewer agents (performance, readability, test-coverage, code-functionality, abstraction) layered on top of `base`. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop` accepts a `reviewerSelection: ReviewerSelector` parameter;
the default (`ReviewerSelector.onlyPreviouslyReporting`) re-runs only the
reviewers that found something last round. Pass `ReviewerSelector.allEveryRound`
for full regression coverage every iteration.

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
- `orca.review.ReviewPrompts` — `Fix`, `SelectReviewers`, `SummarizeLint`
- `orca.review.ReviewerPrompts` — per-reviewer system prompts (compose your own
  list to swap or extend `defaultReviewers`)

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

Until Orca lands on Maven Central, one bootstrap step publishes the artifacts to
your local cache:

```bash
git clone <this repo> && cd orca-sandbox
sbt publishLocal            # first time only
```

After that, run flow scripts directly with `scala-cli`:

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
