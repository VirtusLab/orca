# Working on Orca

Internals and conventions for hacking on the library itself, for both
human contributors and AI assistants. End-user documentation lives in
the [README](README.md).

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
├── flow/       # FlowContext, stage/fail; orca.review (ReviewTypes, ReviewLoop, Reviewers); orca.bug; orca.plan
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

Extra Scala 3 warnings are enabled (`-Wunused:all`, `-Wvalue-discard`,
`-Wnonunit-statement`). They aren't fatal — fix them before committing
rather than relying on the compiler to block.

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

- Prefer plain `sbt <cmd>` in agent/non-interactive shells. Avoid
  `sbt --client` unless you know the persistent sbt server was started with
  the repo's direnv/JDK 21 environment; otherwise the client can attach to a
  stale server running a different Java and fail despite the current shell's
  `JAVA_HOME`.
- `sbt ~test` re-runs tests on save.
- Metals MCP is configured (`.metals/mcp.json`), so AI-assisted tooling can
  query real type info across modules.

## Conventions

### Scala style

- Braceless syntax; explicit return types on every public member.
- No class-level `var`s; mutable state stays in method bodies or
  `AtomicReference`-guarded test helpers.
- Opaque-type aliases for domain string labels (e.g. `Title`, `SessionId`).
- Recoverable failures return `Either[E, T]` where `E <: OrcaFlowException`;
  system failures throw. Use Ox's `.orThrow` at the call site when the
  failure case is genuinely unexpected.

### Code style

- Use proper packaging — related functionality lives in one package.
- Scaladoc describes contract and intent; implementation notes go in inline
  `//` comments alongside the code.
- Tests target exactly one scenario each.

### Library

- Tool event sinks take `OrcaListener` (default `OrcaListener.noop`).
- Domain helpers that bundle an LLM brief follow
  [ADR 0010](adr/0010-prompts-and-helpers-convention.md): sibling
  `XxxPrompts` object + `instructions: String = …` parameter.
- Subprocesses launched from a tool **must** capture stderr — go through
  [`subprocess.QuietProc.call`](tools/src/main/scala/orca/subprocess/QuietProc.scala)
  or a `CliRunner`. os-lib defaults `os.proc(...).call(...)`'s `stderr` to
  `Inherit`, which lets subprocess output bypass the renderer's StatusBar
  and tear the spinner row.

The `direct-style-scala` plugin codifies the Scala-style bullets; re-reading
its chapters before a non-trivial change is recommended.

## Publishing locally

```bash
sbt publishLocal
```

Installs `org.virtuslab::orca:0.0.12` plus its transitive modules
(`orca-tools`, `orca-flow`, `orca-claude`, `orca-codex`) into
`~/.ivy2/local` so a flow script with `//> using repository ivy2Local` can
resolve them.

For an iteration loop while hacking on Orca itself, run sbt in one
terminal with a `~` watch-and-publish:

```bash
sbt "~publishLocal"
```

Every save rebuilds the affected module and refreshes `~/.ivy2/local`.
