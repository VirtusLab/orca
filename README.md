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

You can use Orca to orchestrate development in any language and ecosystem.

Orca assumes that it has configured, logged-in access to Claude, Codex
(depending which provider you use), as well as `gh` and `git`.

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using dep "org.virtuslab::orca:0.0.8"
//> using jvm 21

import orca.{*, given}

flow(OrcaArgs(args)):
  // Plan persists to `.orca/plan-<hash>.md` so a re-run with the same
  // prompt resumes from the first incomplete task. Plan.autonomous.from
  // runs the planner as a single agentic turn (use Plan.interactive.from
  // to let the planner ask clarifying questions). It returns the plan
  // paired with the planner's session; `.value` keeps just the plan (the
  // implementer below opens its own session). `recoverOrCreate` ensures the
  // branch is checked out and the file is on disk before we start.
  val planFile = Plan.defaultPath(userPrompt)
  val plan = stage("Acquire plan"):
    Plan.recoverOrCreate(planFile, "orca: starting work"):
      Plan.autonomous.from(userPrompt, claude).value

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
        // A compile is a cheap sanity gate; correctness is the reviewers'
        // and CI's job, so don't run the heavier full test suite here.
        lintCommand = Some("sbt Test/compile"),
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
* [03-bugfix](examples/03-bugfix/) (issue-driven, red-test-first against a real PR),
* [04-epic](examples/04-epic/) (resumable disk-backed plan with cross-agent
  review).

For convenient editing of Orca flow scripts, with code-completion, you can try
the [Metals](https://scalameta.org/metals/) VSCode extension.

## Built-in tools

The following are available inside a `flow(...) { ... }`:

| Tool | Methods | Purpose |
|---|---|---|
| `claude` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `haiku`/`sonnet`/`opus`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withSelfManagedGit` | Claude Code coding/reviewing agent. Bare `claude` defaults to **Opus with the 1M-token context window** (the long-lived implementer session needs the big window, and reviewers run on it too); use `claude.sonnet` / `claude.haiku` for cheaper one-shot calls (the reviewer picker, lint, the PR summariser). Each `run` returns `(SessionId, output)`. Pre-allocate a session with `claude.newSession` and pass it on every call to keep one conversation alive; omit the arg for a one-shot fresh session. The `autonomous` vs `interactive` mode is always visible at the call site (interactive lives only on `resultAs[O]`). |
| `codex` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `mini`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withSelfManagedGit` | OpenAI Codex coding/reviewing agent. |
| `git` | `createBranch`, `checkout`, `checkoutOrCreate`, `ensureClean`, `commit`, `push`, `currentBranch`, `diff`, `log`, `addWorktree`, `removeWorktree`, `listWorktrees` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushRejected`, `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. |
| `gh` | `createPr`, `updatePr`, `readIssue`, `readIssueComments`, `readPrComments`, `writeComment(pr, body)` / `writeComment(issue, body)`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `createPr` returns `Either[PrCreateFailed, …]` (covers `PrAlreadyExists` / `NoCommitsToPr`); `updatePr` replaces a PR's title + body (refresh a tentative description once the fix lands); `waitForBuild` returns `Either[BuildWaitFailed, …]`. |
| `fs` | `read`, `write`, `list` | Working-tree file I/O. `read` returns `Option[String]` so a missing file is a branch point, not an exception. |

The runtime owns git: every write-capable agent turn is instructed not to run
`git commit`, `git push`, or switch/create branches — it makes edits and leaves
them in the working tree, and the flow commits/branches/pushes via `git.*` at
the right points. This is the default, so a script never has to coax the agent
into leaving git alone; it also keeps `reviewAndFixLoop`'s diff-based reviewer
selection working (a self-committing agent would leave an empty `git.diff()`).
For the rare flow that wants the agent to drive git itself, opt out per-tool
with `claude.withSelfManagedGit` (mirrors `withReadOnly`).

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

The planning entry points form a **mode × operation grid**. The two axes are
orthogonal — every combination is valid. Mode is picked at the call site
(`Plan.autonomous.*` vs `Plan.interactive.*`), mirroring how `LlmTool` itself
splits `autonomous` / `interactive`:

| Operation | Result | `autonomous` (read-only, no human) | `interactive` (agent can `ask_user`) |
|---|---|---|---|
| `from(userPrompt, llm, instructions?)` | `Plan` | plan in one agentic turn | drive the planner conversationally |
| `assessThenPlan(userPrompt, llm, instructions?)` | `Verdict[Plan]` | assess, then `Proceed(plan)` or `Rejection(kind, body)` | same, but can ask the reporter to clarify instead of rejecting |
| `triage(report, llm, instructions?)` | `Triage` | classify a bug report (not-a-bug / untestable / testable) | same, with clarifying questions |

Every cell returns `Sessioned[B, <result>]` — the result paired with the agent
session that produced it. Continue that session into implementation
(`llm.autonomous.run(task, sessioned.sessionId)` — the read-only planning turn's
session is still resumable with write access), or `.value` it and mint a fresh
session via `llm.newSession`. Destructure positionally when you want both:
`val Sessioned(session, plan) = Plan.autonomous.from(...)`.

`assessThenPlan` returns a `Verdict`: `Verdict.Proceed(plan)` to implement, or
`Verdict.Rejection(kind, body)` — a follow-up question, critique, or rebuff the
caller surfaces back to the reporter. `triage` returns a `Triage` sum type the
caller pattern-matches (`NotABug` / `Untestable` / `Testable`).

Persistence + iteration helpers:

| Method | Use |
|---|---|
| `Plan.{autonomous,interactive}.loadOrGenerate(file, userPrompt, llm, instructions?)` | Idempotent plan acquisition: parse `file` if it exists (resume), otherwise generate (in the chosen mode) and persist as markdown. |
| `Plan.defaultPath(userPrompt, workDir?)` | Returns `<workDir>/.orca/plan-<hash>.md` — the conventional persistent-plan path. `<hash>` is the first 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars, so unrelated prompts in the same repo don't collide. |
| `Plan.recoverOrCreate(file, stashMessage?)(generate)` | Resume from `file` if it exists, else `ensureClean` + evaluate `generate`, check out the plan's branch, and persist. The acquisition entry point for resumable flows. |
| `Plan.recover(file)` | Lower-level resume-from-crash: if `file` exists, stash pending edits (`git stash pop` recovers them), switch to the plan's branch, parse, return `Some(plan)`; else `None`. |
| `Plan.implementTaskLoop(file, plan)(body)` / `Plan.implementTaskLoop(plan)(body)` | Iterate `plan` running `body(task)` per task, committing each. The `file` overload also ticks the on-disk checkbox and removes the file at the end (resumable); the file-less overload tracks completion in memory (for flows with their own non-restartable state machine). |
| `Plan.persistComplete(file, title)` | Mark one task complete on disk. Lower-level primitive that the `file` loop is built on. |

Picking interactive vs autonomous is visible at the call site rather than
hidden behind a parameter default — `Plan.interactive.*` and `Plan.autonomous.*`
are sibling namespaces with the same method shapes.

Persistent plans are the default mode for multi-task flows — `implement.sc`,
`implement-interactive.sc`, `epic.sc`, and `issue-pr.sc` all use
`Plan.defaultPath` + `Plan.recover` + `Plan.implementTaskLoop`. See ADR
[0013](adr/0013-persistent-plans.md) for the convention and migration notes.

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(command, llm, instructions?)` | Run a shell lint, write its combined output to a temp file, and have `llm` read and summarise it as a `ReviewResult` (file, not prompt, so unbounded output can't overflow the context). |
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
- `orca.plan.PlanPrompts` — `Planning`, `AssessThenPlan`, `Triage`
- `orca.pr.PrPrompts` — `Summarise`
- `orca.review.ReviewLoopPrompts` — `Fix`, `SelectReviewers`, `SummariseLint`
- `orca.review.ReviewerPrompts` — per-reviewer system prompts (compose your own
  list to swap or extend `allReviewers`/`minimalReviewers`)

The lower-level per-call wrappers (autonomous/interactive/retry) are a
separate layer — replace the whole set via `flow(prompts = ...)`. See ADR
[0010](adr/0010-prompts-and-helpers-convention.md) for the full convention.

## Data structures

Common types you'll see in flow scripts. All `derives JsonData`, so the agent
can generate them as structured output via `claude.resultAs[T]`:

- **`orca.plan.Plan(epicId, tasks)`** — list of tasks the agent generates in
  one round-trip; the same type backs both in-memory use (`Plan.*.from`) and
  the markdown-persisted resume path (`Plan.*.loadOrGenerate`). `epicId` is a
  kebab-case identifier used as the git branch name for the whole plan.
- **`orca.plan.Task(title, description, completed?)`** — `title` is the
  human-readable label shown in the event log and used as the
  `## Task: <title>` markdown header when persisted.
- **`orca.plan.Sessioned(sessionId, value)`** — every `Plan.{autonomous,
  interactive}.*` operation returns one: the result paired with the agent
  session that produced it, so the caller can continue that session into
  implementation or `.value` it and start fresh.
- **`orca.plan.Verdict[A]`** — `Verdict.Proceed(value)` or
  `Verdict.Rejection(kind, body)` (kind ∈ Question / Critique / Rebuff).
  Returned by `assessThenPlan` as `Verdict[Plan]`.
- **`orca.plan.Triage`** — sum type returned by `triage`: `NotABug`,
  `Untestable`, or `Testable` — each carrying exactly the fields its branch
  needs.
- **`orca.plan.BugReportMatch`** — the agent's decision on whether a CI failure
  matches the original report.
- **`orca.Title`** — opaque alias of `String` shared by `Task.title` and
  `ReviewIssue.title`. Construct via `Title("…")`; recover the string with
  `.value`. Keeps short labels from being silently swapped with descriptions
  or raw user input.
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

Apache 2.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 VirtusLab [https://virtuslab.com](https://virtuslab.com).
