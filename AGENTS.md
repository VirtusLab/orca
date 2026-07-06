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
├── tools/      # tool traits + os-backed impls (git/gh/fs), LLM SPI + session durability, InStage, events, subprocess
├── flow/       # stage/display/fail + FlowContext/FlowControl; orca.{plan,review,pr,progress}
├── claude/ codex/ gemini/ opencode/ pi/   # one module per coding-agent backend
└── runner/     # flow() entry, DefaultFlowContext, FlowLifecycle, terminal UI
```

Dependency graph:

```
tools   (standalone)
  ├── flow                          → tools
  ├── claude / codex / gemini /
  │     opencode / pi               → tools
  └── runner                        → tools + flow + all five backends
```

The runner module owns the `flow` entry point (`package orca`) and wires
defaults via `DefaultFlowContext` (`package orca.runner`); the stage
setup/teardown/recovery state machine is `orca.runner.FlowLifecycle`. The
terminal UI lives in `orca.runner.terminal`, behind an `Interaction`, so
swapping it for a Slack or HTTP equivalent is one substitution at the call
site rather than rewiring modules.

The user-facing surface lives in `package orca` (the `flow` entry, the tool
accessors — including `agent`, the backend-agnostic leading-agent accessor (an
ordinary `FlowContext` member typed `Agent[ctx.LeadB]`, where `flow[B]` captures
the selector's backend tag into the `FlowContext { type LeadB }` member) —
`stage`/`display`/`fail`, `JsonData`, `OrcaArgs`). Implementations
live in focused subpackages: `orca.tools` (os-backed git/gh/fs impls + their
traits), `orca.agents` + `orca.backend` (LLM SPI, `SessionSupport`/
`SessionRegistry`, conversation driver), `orca.subprocess` (subprocess shim),
`orca.events` (event bus), one `orca.tools.<backend>` per coding agent, and
`orca.runner` / `orca.runner.terminal`
(wiring + terminal UI). The flow module adds `orca.{plan,review,pr,progress}`.

## The stage-bound runtime

The flow runtime is specified in [ADR 0018](adr/0018-stage-bound-flow-runtime.md) —
read it before touching `stage`, the progress log, or sessions. The invariants
most easily broken:

- **Capability gating.** Three compile-time capabilities gate side effects:
  `FlowContext` (reads + emit; thread-safe), `FlowControl <: FlowContext`
  (authority to start a stage; thread-affine), and the opaque `InStage` token
  (in `tools`, `package orca`). Every mutating tool method — git writes,
  `fs.write`, `gh` writes, every `agent.*.run` — takes `(using InStage)`, which
  only a `stage` body mints. Don't relax this: don't mint `InStage.unsafe`
  outside the runtime (`Flow` / `FlowLifecycle` / `Session`), and don't drop a
  `(using InStage)` to "make it compile" — thread it up to the nearest stage.
  `orcacaps.InStageNegativeTest` pins that a mutation outside a stage fails to
  compile.

- **Progress log + recovery.** A run commits `.orca/progress-<hash>.json` (hash =
  prompt, so the path is branch-independent) with one entry per completed stage;
  a re-run replays recorded entries and skips them. The header is untrusted on
  load — `orca.progress.RecoveryCheck` validates it (safe ref, prompt-hash match,
  protected-branch refusal) before any destructive git op.

- **Sessions.** Durability is ONE structural choice per backend:
  `AgentBackend.sessions: SessionSupport[B]` is either `Ephemeral(registry)`
  (pi — nothing survives a process restart) or `Durable(registry, probe)`
  (claude/codex/gemini/opencode — sessions outlive the process). `Agent`
  derives `sessionExists` / `resumeWireId` / `registerResumeWireId` as `final`
  methods over the single `sessionSupport` hook, so a concrete tool can't wire
  one session operation while silently defaulting the others — the
  half-wiring that shipped resume bugs in both claude and codex is
  unrepresentable now. Underneath, `SessionRegistry` still has two shapes:
  `ClaimedOnce` (claude/pi — the client id IS the wire id) and
  `ClientToServer` (codex/gemini/opencode — a server-minted id learned from the
  protocol); and `SessionId[B]` (the client-side handle) is split from
  `WireSessionId[B]` (what actually goes on the wire) — `SessionId#onWire` is
  the only client→wire crossing. `sessionExists` stays a best-effort,
  non-destructive probe; when it can't confirm a live session the flow
  re-seeds, the uniform fallback that holds on every backend.

  Sessions have named identity: `agent.session(name, seed)` keys a
  `SessionRecord` by `(name, occurrence)` — stage-style, via
  `FlowControl.nextSessionOccurrence(name)` — so reordering or conditionally
  skipping *other* `session(...)` calls between runs doesn't silently re-key
  this one. Each record also carries the minting agent's `backend` tag, so
  `FlowLifecycle.rehydrateSessions` replays a resumed run's resume wire ids
  into the record's own backend's agent rather than always the lead
  (untagged/older records fall back to the lead; a tag matching none of the
  context's accessors is skipped, not guessed).

- **Tool enforcement.** `AgentConfig.tools: ToolSet` (ReadOnly/NetworkOnly/Full)
  and `autoApprove: AutoApprove` (All/Only) request a restriction, but each
  backend enforces it differently. `AgentBackend.enforcement(tools, autoApprove)`
  surfaces the actual guarantee as the `Enforcement` enum — **Hard** (mechanically
  blocked: permission mode / sandbox / allowlist), **SandboxApprox** (a coarser
  sandbox, semantics widened), **PromptOnly** (only the prompt forbids it), or
  **Ignored** (not encoded; depends on backend/server config outside orca):

  | tools, approve  | claude | codex         | gemini  | opencode | pi        |
  |-----------------|--------|---------------|---------|----------|-----------|
  | ReadOnly, *     | Hard   | Hard          | Hard    | Hard     | Hard      |
  | NetworkOnly, *  | Hard   | PromptOnly    | Hard    | Hard     | PromptOnly|
  | Full, All       | Hard   | Hard          | Hard    | Ignored  | Ignored   |
  | Full, Only(_)   | Hard   | SandboxApprox | Ignored | Ignored  | Ignored   |

  The matrix is machine-checked in
  `runner/src/test/scala/orca/runner/EnforcementTableTest.scala` (the source of
  truth); per-cell rationale lives in each backend's `*Args.enforcement`.

- **Conversation events.** The event grammar (turn boundaries, `Option` tool
  names) is specified on `ConversationEvent`'s scaladoc and pinned per backend
  by `ConversationEventConformance` assertions in each module's tests.

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
ORCA_INTEGRATION=1 sbt "claude/testOnly orca.tools.claude.ClaudeIntegrationTest"
ORCA_INTEGRATION=1 sbt "tools/testOnly orca.tools.OsGitHubIntegrationTest"
ORCA_INTEGRATION=1 sbt "runner/testOnly orca.runner.terminal.ScalaCliSmokeTest"
```

| Suite | Needs |
|---|---|
| `{Claude,Codex,Gemini,Opencode,Pi}IntegrationTest` (one per `orca.tools.<backend>`) | that backend's CLI authenticated |
| `OsGitHubIntegrationTest` | `gh` authenticated |
| `ScalaCliSmokeTest` | `scala-cli`; runs `sbt publishLocal` internally |

Unit tests use in-memory fakes (`StubCliRunner` / `SpawnStubCliRunner`,
`FakeAgent`, `FakePipedCliProcess`, `TestFlowContext` / `TestFlowControl`) and
the shared `orca.testkit.GitRepo` temp-repo fixture (published via `tools %
test->test`) — no network, no real filesystem outside `os.temp.dir()`.

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

Installs `org.virtuslab::orca:0.0.14` plus its transitive modules
(`orca-tools`, `orca-flow`, and the five backends
`orca-{claude,codex,gemini,opencode,pi}`) into `~/.ivy2/local` so a flow script
with `//> using repository ivy2Local` can resolve them.

For an iteration loop while hacking on Orca itself, run sbt in one
terminal with a `~` watch-and-publish:

```bash
sbt "~publishLocal"
```

Every save rebuilds the affected module and refreshes `~/.ivy2/local`.
