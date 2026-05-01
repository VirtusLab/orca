# Orca

// TODO: make the readme more concise. Keep only essential and relevant details for the end-user

**Describe what you want, ship it.** Orca turns a one-line prompt into a
sequence of concrete coding tasks, implements each one through an LLM coding
agent (Claude Code or Codex), reviews the changes with a pod of
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
//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.{*, given}
import orca.plan.simple.{SimplePlan, Task}

// `args` is scala-cli's script argv; `OrcaArgs(args)` parses the
// positional prompt and flags. Pass `OrcaArgs()` for scripts that take
// no CLI input; pass overrides (e.g. `git = Some(myGit)`) as extra
// named arguments.
flow(OrcaArgs(args)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[SimplePlan].interactive(userPrompt)

  // 2. Implement each task on its own branch and review locally.
  // Commit *after* the review-and-fix loop so any review-driven
  // edits land in the same commit as the original implementation.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.shortSummary}"):
      git.createBranch(task.branchName)
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

The `{*, given}` selector is load-bearing: plain `import orca.*` leaves
Scala 3's given instances behind, and the flow DSL relies on them to bridge
`derives JsonData` to the underlying Schema and codec during macro
expansion. The rest is just Scala — if you need to override a tool, pass
it as an extra named argument: `flow(args, git = Some(myGit)): ...`.

```bash
scala-cli run ship.sc -- "Add a rate-limiter to the /login endpoint"
```

While Orca runs the terminal output is two zones: an **event log** that
grows line-by-line as stages start and tools fire, and a **status line**
pinned to the bottom showing the active stage with a braille spinner. Stage
completions don't print — the next event implicitly tells you the previous
one finished. Indentation tracks nesting (two spaces per level).

Quick legend:

| Glyph | Meaning                                                |
| ----- | ------------------------------------------------------ |
| `▶`   | Stage starting, or a single-line `Step` (branch switch, etc.) |
| `▸`   | User's prompt at the start of an interactive session   |
| `●`   | Assistant prose                                        |
| `⏺`   | Tool call (path / command / query in grey)             |
| `⎿`   | Tool result (truncated to one line)                    |
| `✖`   | Error                                                  |
| `?`   | Approval request                                       |

Colours and animation auto-disable when stderr isn't a terminal (CI,
redirected output). `NO_COLOR=1` and `ORCA_NO_ANIMATION=1` force them off.
The full design — two-zone rationale, suppression rules, the
subprocess-stderr discipline — lives in
[ADR 0008](adr/0008-terminal-output-design.md).

## What you need

- **Java 21+** (Orca uses virtual threads).
- **[scala-cli](https://scala-cli.virtuslab.org/)** to run the flow script.
- **[claude](https://docs.claude.com/en/docs/claude-code)** — the Claude Code
  CLI, signed in. See [Authenticating the coding agents](#authenticating-the-coding-agents).
- **[gh](https://cli.github.com/)** — the GitHub CLI, signed in (`gh auth
  login`). Only needed if your flow calls `gh.createPr`, `gh.waitForBuild`,
  etc.

That's it. There's no runtime to install, no long-lived service to manage;
each flow run is one `scala-cli` process.

## Authenticating the coding agents

Orca shells out to a per-backend CLI for every LLM call — Claude Code for
the `claude` tool, and the OpenAI Codex CLI for the `codex` tool.
Each CLI handles its own auth; Orca itself stores no secrets.

### Claude

1. Install the Claude Code CLI by following the
   [official install guide](https://docs.claude.com/en/docs/claude-code/setup).
   On macOS and Linux the one-liner is:

   ```bash
   curl -fsSL https://claude.ai/install.sh | bash
   ```

2. Authenticate. Pick one:

   - **Claude.ai subscription / Anthropic Console login** (recommended for
     interactive use):

     ```bash
     claude auth login
     ```

     Follow the browser flow. Credentials are cached in `~/.claude.json`.

   - **API key** (recommended for CI and headless environments):

     ```bash
     export ANTHROPIC_API_KEY=sk-ant-...
     ```

     Orca picks it up via the Claude CLI automatically. Store it in your
     shell profile, or your CI runner's secret store — Orca doesn't read
     it directly.

   ⚠️ **`ANTHROPIC_API_KEY` wins over OAuth.** The Claude CLI always
   prefers the env var when it's set, so an old `sk-ant-...` in your
   `.bashrc` / `.zshrc` / shell history will silently route your orca
   runs to pay-per-call billing (and trip its own rate limit separately
   from your subscription) even after a successful `claude auth login`.
   Run `env | grep ANTHROPIC_API_KEY` before assuming OAuth is active;
   `unset ANTHROPIC_API_KEY` (or start a fresh shell without it) to fall
   back to the cached subscription.

3. Verify:

   ```bash
   claude auth status     # shows which credential path is active
   claude -p "say hi in three words"
   ```

   If `auth status` reports the source you intended and the second
   command prints a short greeting, Orca will reach Claude the same way.

### Codex

The `codex` module wraps the [OpenAI Codex CLI](https://github.com/openai/codex)
behind the same `LlmTool` trait Claude uses, so flow scripts can swap
`claude` for `codex` without other changes. The driver speaks the
`codex exec --json` JSONL stream over stdio — see
[ADR 0007](adr/0007-codex-exec-jsonl-driver.md) for the protocol shape
and the parity gaps vs. Claude (no per-token streaming, no mid-turn
tool approvals; `LlmConfig.autoApprove` is pre-baked into the spawn
flags).

1. Install the Codex CLI per
   [OpenAI's docs](https://github.com/openai/codex#installation).

2. Authenticate with either:

   - `codex login` for an interactive ChatGPT-account flow, or
   - `export OPENAI_API_KEY=sk-...` for headless use.

3. Verify with `codex --version` and a trivial prompt.

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
and Codex backends, and terminal UI are all working end-to-end. Maven
Central publishing is the remaining piece — see [`plan.md`](plan.md) for
the epic-level breakdown.

## Documentation

- [`design.md`](design.md) — full architecture and design rationale.
- [`plan.md`](plan.md) — development plan with per-epic status.
- [`adr/`](adr/) — architecture decision records (backend abstraction,
  stream-json driver, terminal output design, etc.).

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
├── tools/      # tool interfaces + os-backed impls + structured I/O + event bus
├── flow/       # FlowContext, stage/fail/fixLoop/reviewAndFixLoop/lint, review types
├── claude/     # Claude Code backend + DefaultClaudeTool + DefaultLlmCall
├── codex/      # Codex backend (codex exec --json over stdio)
└── runner/     # flow() entry + DefaultFlowContext + terminal layer
```

Dependency graph:

```
tools   (standalone)
  ├── flow       → tools
  ├── claude     → tools
  ├── codex      → tools
  └── runner     → tools + flow + claude + codex
```

The runner module owns the `flow` entry point (`package orca`) and wires
defaults via `DefaultFlowContext` (`package orca.runner`). Its terminal UI
lives in its own sub-package, `orca.runner.terminal`, so swapping it for a
Slack or HTTP equivalent is a matter of substituting one `Interaction` at the
call site rather than rewiring modules.

Only the user-facing surface lives in `package orca` (the `flow` entry
point, the tool traits, the accessors, `JsonData`, `OrcaArgs`).
Implementations live in focused subpackages: `orca.tools.fs` /
`orca.tools.git` / `orca.tools.github` (os-backed tool impls),
`orca.tools.claude` / `orca.tools.codex` (LLM backends), `orca.subprocess`
(subprocess shim), `orca.io` (structured-I/O plumbing), `orca.runner` /
`orca.runner.terminal` (wiring and terminal UI).

### Build and test

```bash
sbt compile                             # build every module
sbt test                                # unit tests across all modules
sbt "flow/test"                         # scope to one module
sbt "flow/testOnly orca.FixLoopTest"    # scope to one suite
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
ORCA_INTEGRATION=1 sbt "tools/testOnly orca.tools.OsGitHubIntegrationTest"
ORCA_INTEGRATION=1 sbt "runner/testOnly orca.runner.terminal.ScalaCliSmokeTest"
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
- Subprocesses launched from a tool **must** capture stderr — go through
  [`subprocess.QuietProc.call`](tools/src/main/scala/orca/subprocess/QuietProc.scala)
  or a `CliRunner`. os-lib defaults `os.proc(...).call(...)`'s `stderr` to
  `Inherit`, which lets subprocess output bypass the renderer's StatusBar
  and tear the spinner row.

The `direct-style-scala` plugin codifies these; re-reading its chapters
before a non-trivial change is recommended.

### Publishing locally

```bash
sbt publishLocal
```

Installs `com.virtuslab::orca:0.1.0-SNAPSHOT` plus its transitive modules
(`orca-tools`, `orca-flow`, `orca-claude`, `orca-codex`) into
`~/.ivy2/local` so a flow script with `//> using repository ivy2Local` can
resolve them.

For an iteration loop while hacking on Orca itself, run sbt in one terminal
with a `~` watch-and-publish:

```bash
sbt "~publishLocal"
```

Every save rebuilds the affected module and refreshes `~/.ivy2/local`. In a
second terminal, re-run a flow script with Coursier's cache bypassed so the
freshly-published snapshot is picked up:

```bash
scala-cli run --ttl 0 examples/spinner-demo.scala
```

## License

Not yet set.
