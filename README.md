# Orca

**Describe what you want, ship it.** Orca turns a one-line prompt into a
sequence of concrete coding tasks, implements each one through an LLM coding
agent (Claude Code today, Codex on the way), reviews the changes with a pod of
specialist reviewers, and opens a PR — all driven by a small, explicit script
you control.

## How it works

You write a short flow script describing the *shape* of your development
loop. Orca handles the orchestration:

1. **Plan.** A planning LLM reads your prompt, consults the codebase, and
   produces a structured list of subtasks — each with a branch name and a
   description. You can review the plan interactively before anything is
   committed.
2. **Implement.** For every subtask, Orca asks the coding agent to make the
   change on a fresh git branch. The agent edits files, runs tests, and
   commits.
3. **Review.** A configurable pod of reviewer agents (performance, readability,
   test coverage by default; or your own) evaluates the diff in parallel.
   Issues above a confidence threshold go back to the coder for a fix; the
   loop continues until the reviewers are happy or a cap is hit.
4. **Ship.** Orca opens a pull request, waits for CI, and leaves a summary of
   any issues the reviewers explicitly decided to accept.

Because the flow is plain code (loops, conditionals, types) you can change any
of it: swap backends, add a Slack approval stage, run lints, skip planning
when the prompt already names a specific file, feed the task list from JIRA.

## A full flow

Save this as `ship.sc` and run it with your task:

```scala
//> using dep "com.virtuslab::orca-cli:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-core:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-claude:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-codex:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.*

case class Task(branchName: String, description: String)
    derives Schema, ConfiguredJsonValueCodec

case class Plan(tasks: List[Task])
    derives Schema, ConfiguredJsonValueCodec

orca:
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("plan"):
    claude.result[Plan].interactive(userPrompt)

  // 2. Implement each task on its own branch, review, and PR.
  for task <- plan.tasks do
    stage(s"implement: ${task.description}"):
      git.createBranch(task.branchName)
      claude.continueSession(sessionId, s"Implement ${task.description}")
      git.commit(s"Implement ${task.description}")
      git.push()

      val pr = gh.createPr(task.description, body = "")
      reviewAndFix(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.description,
        lintCommand = Some("sbt scalafmtCheckAll test")
      )
      gh.waitForBuild(pr, timeout = 30.minutes)
```

```bash
scala-cli run ship.sc -- "Add a rate-limiter to the /login endpoint"
```

While Orca runs, the terminal shows the active stage and an animated
orca-and-wave indicator while the agent is thinking. Interactive stages (like
planning) hand the tty to Claude directly so you can steer; autonomous stages
stream progress back through the event bus.

## What you need

- **Java 21+** (Orca uses virtual threads).
- **[scala-cli](https://scala-cli.virtuslab.org/)** to run the flow script.
- **[claude](https://docs.claude.com/en/docs/claude-code)** — the Claude Code
  CLI, signed in (`claude auth login`).
- **[gh](https://cli.github.com/)** — the GitHub CLI, signed in (`gh auth
  login`). Only needed if your flow calls `gh.createPr`, `gh.waitForBuild`,
  etc.

That's it. There's no runtime to install, no long-lived service to manage;
each flow run is one `scala-cli` process.

## Getting set up

Orca isn't on Maven Central yet. Until it is, one bootstrap step publishes the
artifacts to your local cache so `scala-cli` can resolve them:

```bash
git clone <this repo>
cd orca-sandbox
sbt publishLocal            # first time only
```

After that you're done — run flow scripts directly with `scala-cli`:

```bash
scala-cli run ship.sc -- "your task here"
```

## Status

Under active development. The planning, coding, review-and-fix loop, Claude
backend, and terminal UI are all working end-to-end. Codex support and Maven
Central publishing are the remaining pieces — see [`plan.md`](plan.md) for
the epic-level breakdown.

## Documentation

- [`design.md`](design.md) — full architecture and design rationale.
- [`plan.md`](plan.md) — development plan with per-epic status.

---

## Developing Orca

Orca is implemented in Scala 3 on top of [Ox](https://ox.softwaremill.com/)
for structured concurrency, [tapir](https://tapir.softwaremill.com/) for JSON
Schema derivation, and [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala)
for codec generation. To hack on it you also need **sbt 1.12+** in addition
to the runtime requirements above.

### Project layout

```
orca/
├── build.sbt / project/
├── core/       # user-facing API + tools, structured I/O, event bus
├── claude/     # Claude Code subprocess backend
├── codex/      # Codex backend (skeleton, Epic 9)
└── cli/        # orca() entry point, DefaultFlowContext, TerminalInteraction
```

`cli` depends on `core + claude + codex`; each backend depends only on
`core`, so a consumer who only wants Claude doesn't pull in sttp.

Inside `core`, only the user-facing surface lives in `package orca`.
Implementations go into focused subpackages: `orca.tools` (`OsFsTool`,
`OsGitTool`, `OsGitHubTool`), `orca.subprocess` (`CliRunner`, `OsProcCliRunner`,
`StubCliRunner`), `orca.io` (`JsonSchemaGen`, `ResponseParser`,
`DoneMarkerExtractor`).

### Build and test

```bash
sbt compile                             # build every module
sbt test                                # unit tests across all modules
sbt "core/test"                         # scope to one module
sbt "core/testOnly orca.FixLoopTest"    # scope to one suite
```

Compiler warnings are treated as errors (`-Wunused:all`, `-Wvalue-discard`,
`-Wnonunit-statement`).

### Formatting

```bash
sbt scalafmtAll                         # reformat every source in place
sbt scalafmtCheckAll                    # fail if anything would reformat
```

### Integration tests (gated)

Some tests shell out to real external tools and skip by default:

```bash
ORCA_INTEGRATION=1 sbt test

ORCA_INTEGRATION=1 sbt "claude/testOnly orca.claude.ClaudeIntegrationTest"
ORCA_INTEGRATION=1 sbt "core/testOnly orca.tools.OsGitHubIntegrationTest"
ORCA_INTEGRATION=1 sbt "cli/testOnly orca.cli.ScalaCliSmokeTest"
```

| Suite | Needs |
|---|---|
| `ClaudeIntegrationTest` | `claude` authenticated |
| `OsGitHubIntegrationTest` | `gh` authenticated |
| `ScalaCliSmokeTest` | `scala-cli`; runs `sbt publishLocal` internally |

Unit tests use in-memory fakes (`StubCliRunner`, `FakeLlmTool`,
`FakeCliProcess`, `TestFlowContext`) — no network, no real filesystem outside
of `os.temp.dir()`.

### Iterating quickly

- `sbt --client <cmd>` talks to a persistent sbt server; round-trips drop
  from ~20s to ~2s.
- `sbt ~test` re-runs tests on save.
- Metals MCP is configured (`.metals/mcp.json`), so AI-assisted tooling can
  query real type info across modules.

### Conventions

- Braceless syntax; explicit return types on every public member.
- No class-level `var`s; mutable state is confined to method bodies or
  `AtomicReference`-guarded test helpers.
- Ox for structured concurrency; `.handle*` (not `.serverLogic*`) for Tapir
  endpoints.
- Tests target exactly one scenario each.

The `direct-style-scala` plugin codifies these; re-reading its chapters
before a non-trivial change is recommended.

### Publishing locally

```bash
sbt publishLocal
```

Installs `com.virtuslab::orca-{core,claude,codex,cli}:0.1.0-SNAPSHOT` into
`~/.ivy2/local` so a flow script with `//> using repository ivy2Local` can
resolve them.

## License

Not yet set.
