# Orca

Deterministic agentic software development, in Scala.

Orca is a Scala 3 library for defining and executing development workflows —
planning, coding, review, fix, create PR — as composable, type-safe scripts.
The actual coding, reviews, and LLM interactions are delegated to backends
(Claude Code today; Codex on the roadmap). Orca provides the orchestration:
structured I/O, session management, autonomous and interactive execution, tool
integrations, and observability.

```scala
//> using dep "com.virtuslab::orca-cli:0.1.0-SNAPSHOT"
//> using jvm 21
import orca.*
import orca.cli.orca

orca:
  val plan = claude.result[TaskPlan].prompt(userPrompt)
  git.createBranch(plan.branchName)
  // ... coding, review, PR ...
```

## Status

Under active development. Epics 1 through 8 are complete (project bootstrap,
core API, structured I/O, Claude backend, tool implementations, events and
terminal UI, entry point and wiring, library flow helpers). Epic 9 (Codex
backend) and Epic 10 (Maven Central publishing + examples + docs polish) are
still ahead — see [`plan.md`](plan.md) for the full breakdown.

Nothing has been published yet; to try the library, build it locally with
`sbt publishLocal` and reference `0.1.0-SNAPSHOT` (see below).

## What it gives you

- **Flow scripts, not YAML.** Every step is plain Scala: loops, conditionals,
  types, IDE support. `orca:` opens a context where `claude`, `git`, `gh`,
  `fs`, and `userPrompt` are just there.
- **Structured I/O.** Ask an LLM for `result[TaskPlan]` and Orca generates
  a JSON Schema from your Scala type, injects it into the prompt, parses the
  response, and retries with a corrective prompt if the parse fails. Output
  types use `derives Schema, ConfiguredJsonValueCodec` (tapir + jsoniter).
- **Interactive and autonomous stages in one flow.** Planning can be
  conversational (the user collaborates with Claude in the terminal until
  `<<<ORCA_DONE>>>`), coding can be headless, review can fan out to parallel
  reviewer agents via Ox structured concurrency.
- **Pluggable backends and tools.** `LlmBackend[B <: Backend]`, `GitTool`,
  `GitHubTool`, `FsTool`, `Interaction` are traits; production defaults ship
  in the library, and `orca(git = MyGit(), interaction = MySlack())` swaps
  them.
- **Observable.** Every stage transition, tool call, token count, and error
  flows through `OrcaEvent`s. `CostTracker` totals usage; `TerminalInteraction`
  renders stages as colored lines with an animated orca-and-wave spinner while
  the agent thinks.

For the full design rationale, see [`design.md`](design.md).

## Project layout

Multi-module sbt build:

```
orca/
├── build.sbt
├── project/
│   ├── build.properties
│   └── Dependencies.scala
├── core/       # types, traits, structured I/O, events, tool impls (git/gh/fs)
├── claude/     # Claude Code subprocess backend
├── codex/      # Codex backend (skeleton, Epic 9)
├── cli/        # orca() entry, DefaultFlowContext, TerminalInteraction
└── examples/   # .sc scripts (Epic 10)
```

`cli` depends on `core + claude + codex`; each backend depends only on `core`,
so a consumer who only wants Claude doesn't pull in sttp.

## Requirements

- JDK 21 or newer (the library targets `-release 21` and uses Ox virtual
  threads).
- sbt 1.12+.
- For the Claude backend: the `claude` CLI installed and authenticated (`claude
  auth login`).
- For the GitHub tool: `gh` installed and authenticated.
- For running the smoke test: `scala-cli`.

## Getting started as a library user

Orca isn't on Maven Central yet. To try a flow script locally:

```bash
git clone <repo>
cd orca-sandbox
sbt publishLocal            # installs com.virtuslab::orca-*:0.1.0-SNAPSHOT
```

Write a flow script, `hello.sc`:

```scala
//> using dep "com.virtuslab::orca-cli:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-core:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-claude:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-codex:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.*
import orca.cli.orca

orca:
  println(s"Orca says hello. Your task is: $userPrompt")
```

Run it:

```bash
scala-cli run hello.sc -- "implement feature X"
```

## Getting started as a contributor

```bash
sbt compile                 # build everything
sbt test                    # unit tests across core, claude, cli
ORCA_INTEGRATION=1 sbt test # also runs gated integration tests (needs claude,
                            # gh, and scala-cli on PATH)
sbt scalafmtAll             # format every Scala source
```

Metals MCP is configured — open the repo in a Metals-capable editor (VS Code,
IntelliJ, etc.) and you get type info, jump-to-definition, and diagnostics
across all modules. The `.metals/` directory contains the MCP server config.

For AI-assisted development on this repo, the `direct-style-scala`
plugin supplies the canonical conventions: braceless syntax, explicit return
types, no class-level `var`s, Ox for concurrency, `.handle*` for Tapir
endpoints. Compiler warnings are treated as errors (`-Wunused:all`,
`-Wvalue-discard`, `-Wnonunit-statement`).

### Running the Claude backend end-to-end

```bash
ORCA_INTEGRATION=1 sbt "claude/testOnly orca.claude.ClaudeIntegrationTest"
```

This spawns real `claude -p` calls, exercises session resume, and verifies the
stream-json NDJSON parser. Requires an authenticated Claude Code CLI.

## A first flow

Once `sbt publishLocal` has run, this three-stage flow plans a task, asks
Claude to implement it in a fresh branch, and opens a PR — all from a single
script:

```scala
//> using dep "com.virtuslab::orca-cli:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-core:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-claude:0.1.0-SNAPSHOT"
//> using dep "com.virtuslab::orca-codex:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.*
import orca.cli.orca

case class TaskPlan(branchName: String, description: String)
    derives Schema, ConfiguredJsonValueCodec

orca:
  val plan = stage("plan"):
    claude.result[TaskPlan].prompt(userPrompt)

  stage("implement"):
    git.createBranch(plan.branchName)
    claude.ask(s"Implement: ${plan.description}")
    git.commit(s"Implement ${plan.description}")
    git.push()

  stage("review"):
    val pr = gh.createPr(plan.description, plan.branchName)
    reviewAndFix(
      coder = claude,
      sessionId = SessionId[Backend.ClaudeCode.type]("..."),
      reviewers = defaultReviewers(claude),
      task = plan.description,
      lintCommand = Some("sbt scalafmtCheckAll")
    )
    gh.waitForBuild(pr, timeout = 30.minutes)
```

The terminal shows the orca-and-wave animation while each autonomous stage
runs; interactive stages hand the tty to Claude directly.

## Documentation

- [`design.md`](design.md) — full architecture and API rationale.
- [`plan.md`](plan.md) — development plan with per-epic task status.

## License

Not yet set (Epic 10).
