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
accessors — including `planningAgent`/`codingAgent`/`reviewAgent`, the
backend-agnostic role accessors (ADR 0020) — `stage`/`display`/`fail`,
`JsonData`, `OrcaArgs`). Implementations live in
focused subpackages: `orca.tools` (os-backed git/gh/fs impls + their traits),
`orca.agents` + `orca.backend` (LLM SPI, `SessionSupport`,
conversation driver), `orca.subprocess` (subprocess shim), `orca.events`
(event bus), one `orca.tools.<backend>` per coding agent, and `orca.runner` /
`orca.runner.terminal` (wiring + terminal UI). The flow module adds
`orca.{plan,review,pr,progress}`.

`codingAgent: Agent[ctx.CodeB]` (the same holds for `planningAgent`/
`ctx.PlanB` and `reviewAgent`/`ctx.ReviewB`) is path-dependent, so it only
works inside a straight-line `flow(...)` body sharing one `using
FlowContext` — it doesn't survive being factored into a helper function,
since two `FlowContext` parameters' `CodeB` members don't unify even when
they're the same backend at runtime. A helper should instead take an
explicit `[B <: BackendTag]` type parameter, or bundle the agent and its
durable session as a `FlowSession[B]` handle (`codingAgent.session(name,
seed)`) — see the `CodeB` scaladoc (`flow/src/main/scala/orca/FlowContext.scala`)
for the full rationale.

## The stage-bound runtime

The flow runtime is specified in [ADR 0018](adr/0018-stage-bound-flow-runtime.md) —
read it before touching `stage`, the progress log, or sessions. The invariants
most easily broken:

- **Capability gating.** Four compile-time capabilities gate side effects:
  `FlowContext` (reads + emit; thread-safe), `FlowControl <: FlowContext`
  (authority to start a stage; thread-affine), and a SPLIT pair of stage-bound
  capability tokens (both in `tools`, `package orca`) — `InStage`, the SHARED
  half (`caps.SharedCapability`, fork-capturable): every `agent.*.run` /
  `FlowSession.run` (spend tokens, drive an agent) takes `(using InStage)`,
  and it is safe to capture into a `fork` (the reviewer fan-out's shared
  `InStage` capture is load-bearing); and `WorkspaceWrite`, the EXCLUSIVE half
  (`caps.ExclusiveCapability`, fork-opaque): every git write, `fs.write`, `gh`
  write, and progress-log write takes `(using WorkspaceWrite)`, and it must NOT
  cross a `fork` boundary (two concurrent forks racing on the same git index or
  progress log is exactly what this is meant to catch — ADR 0018 §6). A helper that does
  both (e.g. the lifecycle's `freshRun`, which names the branch via the cheap
  model AND performs the setup git writes) takes BOTH; `Flow`'s
  `recordAndCommit` instead mints its own tokens through the `RuntimeInStage`
  door. Only a `stage` body mints — and is handed both tokens together. Don't relax
  this: production mints go through `orca.RuntimeInStage.token()` /
  `orca.RuntimeInStage.workspaceToken()`, the single named door (a grep for
  `RuntimeInStage` is the whole whitelist of privileged callers);
  `InStage.unsafe` / `WorkspaceWrite.unsafe` themselves are called only by
  `RuntimeInStage` and tests. Don't call either `unsafe` directly outside that
  door, and don't drop a `(using InStage)` / `(using WorkspaceWrite)` to "make
  it compile" — thread it up to the nearest stage. `orcacaps.InStageNegativeTest`
  pins that a workspace mutation outside a stage fails to compile with the
  `WorkspaceWrite` message and an LLM run outside a stage fails to compile with
  the (distinct) `InStage` message.

  **Capture-checking status:** enforcement of the fork-boundary rule is per
  compilation unit and currently opt-in. The separation rejection fires only
  in files carrying the `captureChecking`/`separationChecking` language
  imports *and* only where the fork thunks are widened to the impure
  `() => T` element type — which `CheckedPar`'s `C^` signature forces at the
  one production call site, `ReviewLoop`'s reviewer fan-out (pinned by
  `CcNegativeCompileTest`; see `CheckedPar`'s scaladoc for the verified
  mechanics). Everywhere else — user flow scripts, examples, the rest of
  orca — the shared/exclusive split is convention, and there is no runtime
  guard on `WorkspaceWrite` (the R12 owner-thread assert covers only the
  `FlowControl`/`stage` surface). Ox itself is not yet capture-checked; once
  it is, rejection moves to `ox.fork` directly and `CheckedPar` is deleted.

- **Progress log + recovery.** A run commits `.orca/progress-<hash>.json` (hash =
  prompt, so the path is branch-independent) with one entry per completed stage;
  a re-run replays recorded entries and skips them. The header is untrusted on
  load — `orca.progress.RecoveryCheck` validates it (safe ref, prompt-hash match,
  protected-branch refusal) before any destructive git op.

- **Sessions.** `AgentBackend.sessions: SessionSupport[B]` is one final class
  built from two per-backend choices: durability —
  `SessionSupport.ephemeral(scheme)` (pi — nothing survives a process restart)
  or `SessionSupport.durable(scheme, probe)` (claude/codex/gemini/opencode —
  sessions outlive the process, including across a restart; opencode persists
  sessions in its own global on-disk store independent of orca's per-run
  `opencode serve` process, so a fresh server spawned after a kill/restart
  resumes a committed `resumeWireId` the same way the file-probed backends do,
  live-verified 2026-07-08) — and the `IdScheme`: `ClientClaimed` (claude/pi —
  the client id IS the wire id, put on the wire at spawn) or `ServerMinted`
  (codex/gemini/opencode — the server mints the wire id, learned from the
  protocol and registered after the turn). `Agent` derives `willContinue` /
  `resumeWireId` / `registerResumeWireId` as `final` methods over the single
  `sessionSupport` hook, so a concrete tool can't wire one session operation
  while silently defaulting the others — that half-wiring is unrepresentable.
  `SessionId[B]` (the client-side handle) is split from `WireSessionId[B]`
  (what actually goes on the wire) — `SessionId#onWire` is the only
  client→wire crossing. `willContinue` stays a best-effort, non-destructive
  probe; when it can't confirm a live session the flow re-seeds, the uniform
  fallback that holds on every backend.

  The user surface is three rungs (README "Sessions"): `agent.run` (one-shot)
  / `agent.chat()` (ephemeral `Chat`, fork-safe, `InStage`-only) /
  `agent.session(name, seed)` (durable `FlowSession`, flow-thread-only — the
  owner-thread assert in `FlowSession.run` enforces it at runtime, and the raw
  session-threading doors are `private[orca] runWithSession`, so ephemeral
  continuation is only reachable through a `Chat` handle).

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
  truth) — keep this table in sync with `EnforcementTableTest`; per-cell
  rationale lives in each backend's `*Args.enforcement`.

- **Conversation events.** The event grammar (turn boundaries, `Option` tool
  names) is specified on `ConversationEvent`'s scaladoc and pinned per backend
  by `ConversationEventConformance` assertions in each module's tests.

- **Listener contract.** A listener that throws is logged at ERROR (with its
  stack) and announced once on stderr, then quarantined — permanently
  excluded from dispatch for the rest of the run. The remaining listeners
  still see every event and the flow itself always survives; see
  `EventDispatcher`.

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

### Debugging backend breakage

The coding-agent CLIs auto-update and have broken wire behavior before
(claude 2.1.207 rejected schemas declaring `$schema: …/2020-12` and began
surfacing `--json-schema` output as a visible `StructuredOutput` tool call).
When a flow breaks with no orca change, suspect the CLI first: reproduce
with a direct probe (e.g.
`claude -p "…" --model haiku --json-schema '…'`) before touching orca code,
then fix at the seam (`JsonSchemaGen`, the backend's conversation driver)
with a test pinning the observed wire shape.

### Iterating quickly

- Prefer plain `sbt <cmd>` in agent/non-interactive shells. Avoid
  `sbt --client` unless you know the persistent sbt server was started with
  the repo's direnv/JDK 21 environment; otherwise the client can attach to a
  stale server running a different Java and fail despite the current shell's
  `JAVA_HOME`.
- `sbt ~test` re-runs tests on save.
- For capture-checking experiments, skip sbt: flow's build materialises its
  test classpath into
  `flow/target/scala-*/resource_managed/test/cc-test-classpath.txt`; compile
  fixtures directly with
  `java -cp "$CP" dotty.tools.dotc.Main -classpath "$CP" -d out Fixture.scala`.
  Verify CC enforcement claims this way — empirically, against the pinned
  compiler — before editing docs or wrappers.
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
- A comment must earn its place: keep it succinct and include only what a
  competent reader can't get from the code, names, or signature — a non-obvious
  *why*, an invariant, or a side effect the types can't express. Don't restate
  the README, neighbouring comments, or the obvious, and don't narrate the next
  line. Default to fewer words; delete a sentence that adds nothing rather than
  rewording it.
- Scaladoc describes contract and intent; implementation notes go in inline
  `//` comments alongside the code.
- Comments state present-tense facts about how the code works now — never
  change history ("X no longer does Y").
- Don't explain Scala 3 mechanics (`@implicitNotFound`, sealed traits, given
  resolution, import semantics) in comments — assume the reader knows the
  language. Naming a mechanism is fine; teaching it is noise.
  Project-specific constraints and verified compiler-version-specific
  behavior do belong.
- Never cite development-plan labels (epic/finding numbers) in comments —
  plans are deleted after execution. Cite ADRs, code, or state the fact
  inline.
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
- Any filesystem write under `.orca/` **must** go through
  `OrcaDir.ensureRoot`/`ensureCache`, which refuse a symlinked `.orca` or
  `.orca/cache` component (`OrcaDir.abortIfOrcaComponentSymlink`) before
  creating or writing through it — a committed symlink (git mode 120000)
  would otherwise redirect orca's writes outside the working tree, since orca
  runs flows against arbitrary cloned repos. Prefer `os.write` (`CREATE_NEW`,
  refuses an existing symlink at the leaf) over `os.write.over` (follows a
  leaf symlink); if `.over` is unavoidable, guard the path with `os.isLink`
  first. The check is lstat/no-follow and runs at the earliest `.orca` touch
  (`FlowLock.acquireWorkdir` → `ensureCache`), ahead of any mutation.

The `direct-style-scala` plugin codifies the Scala-style bullets; re-reading
its chapters before a non-trivial change is recommended.

## Publishing locally

```bash
sbt publishLocal
```

Installs `org.virtuslab::orca:0.0.16` plus its transitive modules
(`orca-tools`, `orca-flow`, and the five backends
`orca-{claude,codex,gemini,opencode,pi}`) into `~/.ivy2/local` so a flow script
with `//> using repository ivy2Local` can resolve them.

For an iteration loop while hacking on Orca itself, run sbt in one
terminal with a `~` watch-and-publish:

```bash
sbt "~publishLocal"
```

Every save rebuilds the affected module and refreshes `~/.ivy2/local`.
