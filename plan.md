# Orca — Development Plan

## Project structure

Multi-module sbt project:

```
orca/
├── build.sbt
├── project/
│   ├── build.properties
│   └── Dependencies.scala
├── core/       # types, traits, structured I/O, events
├── claude/     # Claude Code subprocess backend
├── codex/      # Codex WebSocket backend (sttp)
├── cli/        # orca entry point, mainargs, JLine, fansi — depends on all
├── examples/   # .sc scripts for testing and documentation
```

`cli dependsOn (core, claude, codex)`. Backends each `dependsOn core`. This keeps dependency trees clean — users who only need Claude don't pull in sttp.

**Testing**: munit. Subprocess-wrapping code tested via a `CliRunner` trait (stubbed in unit tests, real in integration tests). WebSocket code tested via sttp's `WebSocketStub`. Integration tests gated behind `ORCA_INTEGRATION` env var. JSON Schema round-trips tested with `networknt/json-schema-validator`.

**Metals MCP**: Configure `metals.startMcpServer = true` for AI-assisted development with real type info.

**CI**: GitHub Actions, JDK 21, `sbt +test`. Integration tests in a separate job.

---

## Epic 1: Project Bootstrap ✅

Get a compiling, tested, CI-green project skeleton.

| # | Task | Description | Status |
|---|---|---|---|
| 1.1 | sbt multi-module setup | `build.sbt` with `core`, `claude`, `codex`, `cli` modules. Scala 3 LTS, JDK 21 target. Dependency declarations for all libraries (os-lib, jsoniter-scala, tapir, Ox, sttp, mainargs, JLine, fansi, scribe, munit). | ✅ |
| 1.2 | CI pipeline | GitHub Actions workflow: checkout, setup JDK 21, `sbt +test`. Separate integration test job gated on `ORCA_INTEGRATION`. | ✅ |
| 1.3 | Metals MCP | `.metals/` config with `startMcpServer = true`. Verify Metals indexes the project and exposes type info via MCP. | ✅ |
| 1.4 | Hello-world compilation | A trivial `@main` in `cli` that prints "orca" and a `munit.FunSuite` in `core` that passes. Verify `sbt test` is green and `sbt publishLocal` works. | ✅ |

**Exit criteria**: `sbt test` green, `sbt publishLocal` produces artifacts, CI passes, Metals provides completions.

---

## Epic 2: Core API ✅

All traits, types, and signatures — compilable, no implementations yet. This is the public API surface.

| # | Task | Description | Status |
|---|---|---|---|
| 2.1 | Base types | `Backend` enum, `SessionId` opaque type, `LlmConfig`, `AutoApprove`, `UnapprovedPolicy`, `Usage`. Verify `derives` works for enums. | ✅ |
| 2.2 | Tool traits | `GitTool`, `GitHubTool`, `FsTool` traits. Supporting types: `PrHandle`, `BuildStatus`, `Comment`, `CommitInfo`. | ✅ |
| 2.3 | LLM traits | `LlmTool[B]`, `ClaudeTool`, `CodexTool`, `LlmCall[B, O]`. Verify the type parameter + opaque type + method chaining compiles. | ✅ |
| 2.4 | AgentInput typeclass | `AgentInput` trait, `given` instances for `String` and `ConfiguredJsonValueCodec`. Unit test: serialization of String and a case class. | ✅ |
| 2.5 | Structured types | `ReviewIssue`, `Severity`, `ReviewResult`, `IgnoredIssue`, `IgnoredIssues`, `ReviewContext`, `SelectedReviewers`. Verify `derives Schema, ConfiguredJsonValueCodec` compiles. Unit test: serialize/deserialize round-trip. | ✅ |
| 2.6 | Event types | `OrcaEvent` enum, `OrcaListener` trait, `Interaction` trait. | ✅ |
| 2.7 | FlowContext & helpers | `FlowContext` trait, `OrcaFlowException`. Signatures for `stage`, `fail`, `fixLoop`, `reviewAndFix`, `lint`. `PromptTemplate` trait. | ✅ |
| 2.8 | Backend abstraction | `LlmBackend[B]` trait, `LlmResult[B]`, `InteractiveHandle[B]`. | ✅ |

**Exit criteria**: `sbt compile` green across all modules. All types and traits exist with correct signatures. Unit tests for AgentInput and structured type round-trips pass.

---

## Epic 3: Structured I/O Pipeline ✅

The input serialization → prompt construction → response parsing chain. Testable entirely without a backend.

| # | Task | Description | Status |
|---|---|---|---|
| 3.1 | JSON Schema generation | Given a Tapir `Schema[O]`, produce a JSON Schema string via `TapirSchemaToJsonSchema`. Unit test: generate schema for `ReviewResult`, validate it with `json-schema-validator`. | ✅ |
| 3.2 | Default prompt templates | Implement `DefaultPromptTemplate` — `autonomous()` and `interactive()` methods. Unit test: template output contains input JSON, output schema, and (for interactive) `<<<ORCA_DONE>>>` instructions. | ✅ |
| 3.3 | Response parsing | Parse a JSON string into `O` using jsoniter-scala. Handle: valid JSON, malformed JSON (throw), JSON wrapped in markdown fences (strip). Unit test: valid input, fenced input, invalid input. | ✅ |
| 3.4 | Corrective retry prompt | When parsing fails, construct a retry prompt with the error message. Unit test: verify prompt includes original input + error. | ✅ |
| 3.5 | ORCA_DONE extraction | Given raw agent output (possibly containing prose before the marker), extract the JSON payload after `<<<ORCA_DONE>>>`. Unit test: marker at end, marker mid-text, no marker (error). | ✅ |

**Exit criteria**: Schema generation, prompt construction, and response parsing all work in isolation. No backend needed.

---

## Epic 4: Claude Code Backend ✅

First real backend. Subprocess-based, testable with a `CliRunner` abstraction.

| # | Task | Description | Status |
|---|---|---|---|
| 4.1 | CliRunner abstraction | Trait with `run(args, stdin, env, cwd)` returning `(exitCode, stdout, stderr)`. Real implementation via `os.proc`. Test stub returning canned responses. | ✅ |
| 4.2 | Headless invocation | Implement `runHeadless`: construct `claude -p` command with flags (`--output-format json`, `--append-system-prompt-file`, `--allowedTools`, `--permission-mode`). Parse JSON response into `LlmResult`. Unit test with stubbed CliRunner. | ✅ |
| 4.3 | Session management | Implement `continueHeadless` with `--resume <id>`. Parse `session_id` from response. Unit test: verify `--resume` flag is passed, session ID extracted. | ✅ |
| 4.4 | NDJSON streaming | Parse `stream-json` output line by line. Emit `OrcaEvent.LlmOutput` for each text event. Unit test with canned NDJSON. | ✅ |
| 4.5 | Stop hook generation | `prepareWorkspace`: write `.claude/settings.json` with the Stop hook that detects `<<<ORCA_DONE>>>` and writes the sentinel file. Unit test: verify generated JSON is valid, hook script is correct. | ✅ |
| 4.6 | Interactive mode | `launchInteractive`: spawn `claude` (no `-p`) with `--session-id` and `--append-system-prompt-file`. Terminal handoff via `os.Inherit`. `awaitTermination`: watch for sentinel file, then SIGINT. Unit test with stubbed CliRunner. | ✅ |
| 4.7 | Config mapping | Map `LlmConfig` fields to Claude CLI flags: `autoApprove` → `--permission-mode`, model → `--model`, etc. Unit test: config → args mapping. | ✅ |
| 4.8 | Integration tests | Real `claude -p` calls (gated). Test: headless prompt returns valid JSON, session resume works, streaming emits events. | ✅ |

**Exit criteria**: Claude Code backend passes unit tests with stubbed CLI. Integration tests pass against real `claude` (when available).

---

## Epic 5: Tool Implementations ✅

Git, GitHub, Fs wrappers. Independently testable.

| # | Task | Description | Status |
|---|---|---|---|
| 5.1 | FsTool implementation | Wrap `os.read`, `os.write`, `os.list` with glob support. Unit test against a temp directory. | ✅ |
| 5.2 | GitTool implementation | Wrap `git` CLI via `os.proc`. Unit test against a temp git repo (`git init` in a temp dir). Test: createBranch, checkout, commit, diff, log. | ✅ |
| 5.3 | GitHubTool implementation | Wrap `gh` CLI. `waitForBuild` polls `buildStatus` with a configurable interval. Unit test with stubbed CliRunner for `gh` commands. | ✅ |
| 5.4 | Integration tests | Real `git` commands in a temp repo. Real `gh` commands (gated on GitHub auth). | ✅ |

**Exit criteria**: All tool wrappers pass unit tests. Git integration tests run against real repos.

---

## Epic 6: Events & Terminal UI

Event dispatch, terminal rendering, interaction handling.

| # | Task | Description | Status |
|---|---|---|---|
| 6.1 | Event dispatcher | Synchronous event dispatch to registered `OrcaListener` list. Unit test: listener receives all emitted events in order. | |
| 6.2 | stage() and fail() | `stage` emits `StageStarted`/`StageCompleted`, wraps body. `fail` emits `Error`, throws `OrcaFlowException`. Unit test: verify events emitted. | |
| 6.3 | CostTracker | Listener that accumulates `TokensUsed` events. Prints summary. Unit test: feed events, check totals. | |
| 6.4 | TerminalInteraction | JLine 3 + fansi. Spinner during autonomous stages, colored stage names, streaming LLM output. Terminal handoff (`os.Inherit`) for interactive stages. Manual testing (not easily unit-testable). | |
| 6.5 | Interaction ↔ backend wiring | `TerminalInteraction.runInteractive` saves terminal state, hands to `InteractiveHandle`, restores on return. | |

**Exit criteria**: Event dispatch, stage/fail, CostTracker pass unit tests. Terminal rendering works visually (manual verification).

---

## Epic 7: Entry Point & Wiring

The `orca` function that ties everything together.

| # | Task | Description | Status |
|---|---|---|---|
| 7.1 | CLI argument parsing | Parse `userPrompt` (positional) and `--verbose` via mainargs. Unit test: parse various arg combinations. | |
| 7.2 | `orca` context function | `def orca(...)(...: FlowContext ?=> Unit): Unit`. Creates FlowContext, registers listeners, runs the flow within an Ox scope. | |
| 7.3 | Top-level accessors | `def claude(using FlowContext)`, `def git(using FlowContext)`, etc. Verify they compile and resolve inside `orca:` blocks. | |
| 7.4 | FlowContext construction | Wire up: Claude backend, Codex backend (lazy), all tools, event dispatcher, interaction channel. Support custom overrides (`orca(git = ..., interaction = ...)`). | |
| 7.5 | Retry wiring | Wrap LLM calls with `ox.retry(schedule)`. Corrective retry on parse failure. Unit test: mock backend that fails twice then succeeds. | |
| 7.6 | scala-cli smoke test | Integration test: `sbt publishLocal`, then spawn `scala-cli run` with a minimal `.sc` script that uses the library. Verify exit code 0 and expected output. | |

**Exit criteria**: A minimal `.sc` script runs via `scala-cli` and calls `claude.ask("hello")`. End-to-end path works.

---

## Epic 8: Library Functions

Higher-level flow combinators, built on all previous epics.

| # | Task | Description | Status |
|---|---|---|---|
| 8.1 | fixLoop | Implement the evaluate → fix → re-evaluate loop with max iterations and ignored issue tracking. Unit test with mock evaluate/fix functions. | |
| 8.2 | reviewAndFix | Parallel reviewers via `ox.par`, confidence filtering, optional lint, stage management. Uses fixLoop internally. Unit test with mock reviewers. | |
| 8.3 | lint | Run a shell command, capture output, summarize via LLM into `ReviewResult`. Unit test with a canned lint output. | |
| 8.4 | defaultReviewers | Pre-configured reviewer agents (performance, readability, test coverage). System prompts. | |
| 8.5 | Integration test | Full flow: code → lint → review → fix loop, with a mock backend. Verify event sequence and final ignored issues. | |

**Exit criteria**: fixLoop, reviewAndFix, lint pass unit tests. Integration test exercises the full loop.

---

## Epic 9: Codex Backend

Second backend. WebSocket-based, independent of Claude.

| # | Task | Description | Status |
|---|---|---|---|
| 9.1 | Codex headless | Spawn `codex exec --json --full-auto`. Parse JSONL events. Extract `thread_id` from `thread.started`, output from `item.completed`, usage from `turn.completed`. Unit test with canned JSONL. | |
| 9.2 | App-server lifecycle | Lazy spawn of `codex app-server`. Port management. Health check (wait for ready). Shutdown via Ox `useInScope`. Auto-restart on crash. Unit test with a mock server process. | |
| 9.3 | WebSocket JSON-RPC client | sttp WebSocket client. Implement `thread/start`, `turn/start`, `thread/resume`, `thread/list`. Unit test with `WebSocketStub`. | |
| 9.4 | Interactive mode | `launchInteractive` via app-server: create thread, start turn, stream events. Detect `<<<ORCA_DONE>>>` in streamed events, stop sending turns. Wire to `Interaction.runInteractive`. Unit test with stubbed WebSocket. | |
| 9.5 | Codex stop hook | For headless mode: generate hook config with `{"continue": false}` output. Unit test. | |
| 9.6 | Integration tests | Real `codex exec` calls (gated). App-server start/stop. | |

**Exit criteria**: Codex backend passes unit tests. Integration tests pass against real `codex`.

---

## Epic 10: Publishing & Documentation

Ship it.

| # | Task | Description | Status |
|---|---|---|---|
| 10.1 | sbt-ci-release setup | Configure sbt-ci-release or sbt-sonatype for Maven Central publishing. GPG signing, Sonatype credentials. | |
| 10.2 | Example scripts | Polished `.sc` files: development flow, planning flow. Placed in `examples/` and tested in CI. | |
| 10.3 | README | Project overview, quickstart, installation, link to design doc. | |
| 10.4 | End-to-end CI test | CI job that `publishLocal` → runs an example `.sc` with a mocked backend → verifies output. | |

**Exit criteria**: Library published to Maven Central. `//> using dep "com.virtuslab::orca:0.1"` works. README and examples exist.

---

## Dependency graph

```
Epic 1 (bootstrap)
  ↓
Epic 2 (core API)
  ↓
  ├── Epic 3 (structured I/O)
  │     ↓
  │     ├── Epic 4 (Claude backend)
  │     └── Epic 9 (Codex backend)  ← can start after Epic 3
  │
  ├── Epic 5 (tool implementations)  ← parallel with 3/4
  │
  └── Epic 6 (events & UI)  ← parallel with 3/4/5
        ↓
Epic 7 (entry point & wiring)  ← needs 2+3+4+5+6
  ↓
Epic 8 (library functions)  ← needs 7
  ↓
Epic 10 (publishing)  ← needs everything
```

Epics 3, 4, 5, 6 can proceed in parallel after Epic 2. Epic 9 (Codex) can start after Epic 3 and proceed in parallel with 4-8. This gives the fastest path to a working end-to-end: 1 → 2 → 3 → 4 → 5 → 7 (skip 6 for terminal, skip 9 for Codex) as a **minimum viable flow**.
