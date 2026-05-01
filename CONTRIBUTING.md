# Contributing to Orca

Internals and conventions for hacking on the library itself. End-user
documentation lives in the [README](README.md).

Orca is implemented in Scala 3 on top of [Ox](https://ox.softwaremill.com/)
for structured concurrency, [tapir](https://tapir.softwaremill.com/) for
JSON Schema derivation, and
[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) for codec
generation. **sbt 1.12+** is needed in addition to the runtime requirements
listed in the README.

## Project layout

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
Slack or HTTP equivalent is a matter of substituting one `Interaction` at
the call site rather than rewiring modules.

Only the user-facing surface lives in `package orca` (the `flow` entry
point, the tool traits, the accessors, `JsonData`, `OrcaArgs`).
Implementations live in focused subpackages: `orca.tools.fs` /
`orca.tools.git` / `orca.tools.github` (os-backed tool impls),
`orca.tools.claude` / `orca.tools.codex` (LLM backends), `orca.subprocess`
(subprocess shim), `orca.io` (structured-I/O plumbing), `orca.runner` /
`orca.runner.terminal` (wiring and terminal UI).

## Build and test

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
`FakeCliProcess`, `TestFlowContext`) — no network, no real filesystem
outside of `os.temp.dir()`.

### Iterating quickly

- `sbt --client <cmd>` talks to a persistent sbt server; round-trips drop
  from ~20s to ~2s.
- `sbt ~test` re-runs tests on save.
- Metals MCP is configured (`.metals/mcp.json`), so AI-assisted tooling can
  query real type info across modules.

## Conventions

- Braceless syntax; explicit return types on every public member.
- No class-level `var`s; mutable state is confined to method bodies or
  `AtomicReference`-guarded test helpers.
- Ox for structured concurrency; `.handle*` (not `.serverLogic*`) for
  Tapir endpoints.
- Tests target exactly one scenario each.
- Subprocesses launched from a tool **must** capture stderr — go through
  [`subprocess.QuietProc.call`](tools/src/main/scala/orca/subprocess/QuietProc.scala)
  or a `CliRunner`. os-lib defaults `os.proc(...).call(...)`'s `stderr` to
  `Inherit`, which lets subprocess output bypass the renderer's StatusBar
  and tear the spinner row.

The `direct-style-scala` plugin codifies these; re-reading its chapters
before a non-trivial change is recommended.

## Publishing locally

```bash
sbt publishLocal
```

Installs `com.virtuslab::orca:0.1.0-SNAPSHOT` plus its transitive modules
(`orca-tools`, `orca-flow`, `orca-claude`, `orca-codex`) into
`~/.ivy2/local` so a flow script with `//> using repository ivy2Local` can
resolve them.

For an iteration loop while hacking on Orca itself, run sbt in one
terminal with a `~` watch-and-publish:

```bash
sbt "~publishLocal"
```

Every save rebuilds the affected module and refreshes `~/.ivy2/local`.
Re-run a flow script with Coursier's cache bypassed so the freshly-
published snapshot is picked up:

```bash
scala-cli run --ttl 0 examples/spinner-demo.scala
```
