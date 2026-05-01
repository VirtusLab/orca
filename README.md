# Orca

**Describe what you want, ship it.** Orca turns a one-line prompt into a
sequence of concrete coding tasks, implements each one through an LLM
coding agent (Claude Code or Codex), reviews the changes with a pod of
specialist reviewers, and opens a PR — all driven by a small, explicit
script you control.

## How it works

You write a short flow script describing the *shape* of your development
loop. Orca handles the orchestration:

1. **Plan.** A planning LLM reads your prompt and produces a structured
   list of subtasks — each with a branch name and a description.
2. **Implement.** For every subtask, Orca asks the coding agent to make
   the change on a fresh git branch.
3. **Review.** A pod of reviewer agents evaluates the diff in parallel.
   Issues above a confidence threshold go back to the coder for a fix
   until the reviewers are happy or a cap is hit.
4. **Ship.** Orca commits, opens a PR, waits for CI, and surfaces any
   issues the reviewers explicitly accepted.

Because the flow is plain code — loops, conditionals, types — you can
swap backends, add a Slack approval, run lints, skip planning when the
prompt names a specific file, feed the task list from JIRA.

## A full flow

Save this as `ship.sc` and run it with your task:

```scala
//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.{*, given}
import orca.plan.simple.SimplePlan

flow(OrcaArgs(args)):
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[SimplePlan].interactive(userPrompt)

  for task <- plan.tasks do
    stage(s"Implement task: ${task.shortSummary}"):
      git.createBranch(task.name)
      claude.continueSession(sessionId, task.description)
      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.shortSummary,
        lintCommand = Some("sbt scalafmtCheckAll test")
      )
      git.commit(s"Implement ${task.shortSummary}")
```

```bash
scala-cli run ship.sc -- "Add a rate-limiter to the /login endpoint"
```

The `{*, given}` selector is required: plain `import orca.*` leaves
Scala 3's given instances behind, and the flow DSL relies on them to
bridge `derives JsonData` to the underlying schema and codec.

Three runnable examples ship with the repo:
[01-simple](examples/01-simple/) (in-memory plan + review),
[02-bugfix](examples/02-bugfix/) (red-test-first against a real PR),
[03-epic](examples/03-epic/) (resumable disk-backed plan with
cross-agent review).

## Built-in tools

Available inside `flow(...) { ... }` as ambient givens — call them
directly:

| Tool | Methods | Purpose |
|---|---|---|
| `claude` | `ask`, `startSession`, `continueSession`, `resultAs[O]`, `haiku`/`sonnet`/`opus`, `withConfig`, `withSystemPrompt` | Claude Code coding/reviewing agent. |
| `codex` | `ask`, `startSession`, `continueSession`, `resultAs[O]`, `mini`, `withConfig`, `withSystemPrompt` | OpenAI Codex coding/reviewing agent. |
| `git` | `createBranch`, `checkoutOrCreate`, `ensureClean`, `commit`, `push`, `currentBranch`, `diff`, `log` | Git operations against the working tree. |
| `gh` | `createPr`, `writeComment`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. |
| `fs` | `read`, `write` | Working-tree file I/O. |

`resultAs[O]` is the structured-output surface; `O` needs a `JsonData[O]`
(provided by `derives JsonData` on a case class) and benefits from an
`Announce[O]` for the friendly summary line that lands in the event log.

## Flow methods

| Method | Use |
|---|---|
| `flow(args, ...)(body)` | Entry point. Sets up the `FlowContext` for the body. |
| `stage(name)(body)` | Wrap an operation in a named stage. Emits `StageStarted`/`StageCompleted` and shows in the status-bar breadcrumb. |
| `fail(message)` | Abort the current stage with an error. |
| `lint(command, llm)` | Run a shell lint, hand the output to `llm`, parse as `ReviewResult`. |
| `reviewAndFixLoop(coder, sessionId, reviewers, task, lintCommand?, ...)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to `coder` to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. |
| `defaultReviewers(base)` | Five canonical reviewer agents (performance, readability, test-coverage, code-functionality, abstraction) layered on top of `base`. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop` accepts a `reviewerSelection: ReviewerSelector`
parameter; the default
(`ReviewerSelector.onlyChangedDimensions`) re-runs only the reviewers
that found something last round. Pass `ReviewerSelector.allEveryRound`
for full regression coverage every iteration.

## Data structures

Common types you'll see in flow scripts. All `derives JsonData`, so the
agent generates them as structured output via `claude.resultAs[T]`.

- **`orca.plan.simple.SimplePlan(tasks)`** — in-memory list of tasks the
  agent generates in one round-trip.
- **`orca.plan.extended.Plan`** — markdown-backed plan persisted to a
  file (`epic.md` by convention) for resumable runs.
- **`orca.plan.Task(name, shortSummary, description, completed?)`** —
  shared by both plan variants. `name` doubles as the git branch in
  simple plans and the `## Task: <name>` header in extended plans.
- **`orca.bug.BugTriage`** / **`orca.bug.BugReportMatch`** — the agent's
  decision on whether a bug can be reproduced as a unit test, and
  whether a CI failure matches the report.
- **`orca.ReviewIssue` / `ReviewResult`** — what reviewer agents return.
  Issues carry severity, confidence, a short summary (shown), and a
  long description (sent to the fixer).
- **`orca.IgnoredIssues(issues: List[IgnoredIssue])`** — what the fix
  step returns: each entry is an issue the fixer chose to set aside,
  with a reason.

## Output

While Orca runs the terminal output is two zones: an **event log** that
grows top-to-bottom as stages and tools fire, and a **status line**
pinned to the bottom showing the active stage breadcrumb with a braille
spinner. Stage completions don't print — the next event implicitly
tells you the previous one finished. Indentation tracks nesting (two
spaces per level).

| Glyph | Meaning |
| ----- | ------- |
| `▶` | Stage start, or a `Step` (single-line note like a branch switch) |
| `▸` | User's prompt at the start of an interactive session |
| `●` | Assistant prose |
| `⏺` | Tool call (path / command / query in grey) |
| `⎿` | Tool result (truncated to one line) |
| `✖` | Error |
| `?` | Approval request |

Colours and animation auto-disable when stderr isn't a terminal.
`NO_COLOR=1` and `ORCA_NO_ANIMATION=1` force them off. The full design
lives in [ADR 0008](adr/0008-terminal-output-design.md).

## What you need

- **Java 21+** and **[scala-cli](https://scala-cli.virtuslab.org/)**.
- **[claude](https://docs.claude.com/en/docs/claude-code)** signed in
  (see below).
- **[codex](https://github.com/openai/codex)** signed in (only if your
  flow uses the `codex` tool).
- **[gh](https://cli.github.com/)** signed in (only if your flow calls
  `gh.createPr`, `gh.waitForBuild`, etc.).

There's no runtime to install, no long-lived service to manage; each
flow run is one `scala-cli` process.

## Authenticating the coding agents

Each CLI handles its own auth; Orca itself stores no secrets.

### Claude

```bash
curl -fsSL https://claude.ai/install.sh | bash   # install
claude auth login                                 # subscription / Console
# OR
export ANTHROPIC_API_KEY=sk-ant-...               # API key (CI / headless)
```

⚠️ `ANTHROPIC_API_KEY` wins over OAuth: an old key in your `.bashrc`
silently routes to pay-per-call billing even after `claude auth login`.
Run `env | grep ANTHROPIC_API_KEY` and `unset` it if you meant to use
the subscription.

### Codex

Install per [OpenAI's docs](https://github.com/openai/codex#installation),
then either `codex login` (ChatGPT account) or
`export OPENAI_API_KEY=sk-...` (headless).

## Getting set up

Until Orca lands on Maven Central, one bootstrap step publishes the
artifacts to your local cache:

```bash
git clone <this repo> && cd orca-sandbox
sbt publishLocal            # first time only
```

After that, run flow scripts directly with `scala-cli`:

```bash
scala-cli run ship.sc -- "your task here"
```

## Documentation

- [`design.md`](design.md) — architecture and design rationale.
- [`adr/`](adr/) — architecture decision records.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — internals, conventions,
  building/testing the library itself.

## License

Not yet set.
