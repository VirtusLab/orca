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
├── codex/      # Codex subprocess backend (WebSocket only if 9.1 probe requires it)
├── cli/        # orca entry point, mainargs, JLine, fansi — depends on all
├── examples/   # .sc scripts for testing and documentation
```

`cli dependsOn (core, claude, codex)`. Backends each `dependsOn core`. The original plan assumed Codex would need sttp for a WebSocket app-server; Epic 9.1 re-evaluates whether `codex exec --json` can drive interactive sessions over stdio the way Claude's stream-json mode does, in which case sttp is not needed.

**Testing**: munit. Subprocess-wrapping code tested via a `CliRunner` trait (stubbed in unit tests, real in integration tests) and the stream-json driver via `FakePipedCliProcess`. If the Codex probe forces a WebSocket fallback, that code is tested via sttp's `WebSocketStub`. Integration tests gated behind `ORCA_INTEGRATION` env var. JSON Schema round-trips tested with `networknt/json-schema-validator`.

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
| 2.1 | Base types | `Backend` enum, `SessionId` opaque type, `AgentConfig`, `AutoApprove`, `UnapprovedPolicy`, `Usage`. Verify `derives` works for enums. | ✅ |
| 2.2 | Tool traits | `GitTool`, `GitHubTool`, `FsTool` traits. Supporting types: `PrHandle`, `BuildStatus`, `Comment`, `CommitInfo`. | ✅ |
| 2.3 | LLM traits | `Agent[B]`, `ClaudeAgent`, `CodexAgent`, `AgentCall[B, O]`. Verify the type parameter + opaque type + method chaining compiles. | ✅ |
| 2.4 | AgentInput typeclass | `AgentInput` trait, `given` instances for `String` and `ConfiguredJsonValueCodec`. Unit test: serialization of String and a case class. | ✅ |
| 2.5 | Structured types | `ReviewIssue`, `Severity`, `ReviewResult`, `IgnoredIssue`, `IgnoredIssues`, `ReviewContext`, `SelectedReviewers`. Verify `derives Schema, ConfiguredJsonValueCodec` compiles. Unit test: serialize/deserialize round-trip. | ✅ |
| 2.6 | Event types | `OrcaEvent` enum, `OrcaListener` trait, `Interaction` trait. | ✅ |
| 2.7 | FlowContext & helpers | `FlowContext` trait, `OrcaFlowException`. Signatures for `stage`, `fail`, `fixLoop`, `reviewAndFix`, `lint`. `PromptTemplate` trait. | ✅ |
| 2.8 | Backend abstraction | `AgentBackend[B]` trait, `AgentResult[B]`, `InteractiveHandle[B]`. | ✅ |

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
| 4.2 | Headless invocation | Implement `runHeadless`: construct `claude -p` command with flags (`--output-format json`, `--append-system-prompt-file`, `--allowedTools`, `--permission-mode`). Parse JSON response into `AgentResult`. Unit test with stubbed CliRunner. | ✅ |
| 4.3 | Session management | Implement `continueHeadless` with `--resume <id>`. Parse `session_id` from response. Unit test: verify `--resume` flag is passed, session ID extracted. | ✅ |
| 4.4 | NDJSON streaming | Parse `stream-json` output line by line. Emit `OrcaEvent.LlmOutput` for each text event. Unit test with canned NDJSON. | ✅ |
| 4.5 | Stop hook generation | `prepareWorkspace`: write `.claude/settings.json` with the Stop hook that detects `<<<ORCA_DONE>>>` and writes the sentinel file. Unit test: verify generated JSON is valid, hook script is correct. | ✅ |
| 4.6 | Interactive mode | `launchInteractive`: spawn `claude` (no `-p`) with `--session-id` and `--append-system-prompt-file`. Terminal handoff via `os.Inherit`. `awaitTermination`: watch for sentinel file, then SIGINT. Unit test with stubbed CliRunner. | ✅ |
| 4.7 | Config mapping | Map `AgentConfig` fields to Claude CLI flags: `autoApprove` → `--permission-mode`, model → `--model`, etc. Unit test: config → args mapping. | ✅ |
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

## Epic 6: Events & Terminal UI ✅

Event dispatch, terminal rendering, interaction handling.

| # | Task | Description | Status |
|---|---|---|---|
| 6.1 | Event dispatcher | Synchronous event dispatch to registered `OrcaListener` list. Unit test: listener receives all emitted events in order. | ✅ |
| 6.2 | stage() and fail() | `stage` emits `StageStarted`/`StageCompleted`, wraps body. `fail` emits `Error`, throws `OrcaFlowException`. Unit test: verify events emitted. | ✅ |
| 6.3 | CostTracker | Listener that accumulates `TokensUsed` events. Prints summary. Unit test: feed events, check totals. | ✅ |
| 6.4 | TerminalInteraction | JLine 3 + fansi. Spinner during autonomous stages, colored stage names, streaming LLM output. Terminal handoff (`os.Inherit`) for interactive stages. Manual testing (not easily unit-testable). | ✅ |
| 6.5 | Interaction ↔ backend wiring | `TerminalInteraction.runInteractive` saves terminal state, hands to `InteractiveHandle`, restores on return. | ✅ |

**Exit criteria**: Event dispatch, stage/fail, CostTracker pass unit tests. Terminal rendering works visually (manual verification).

---

## Epic 7: Entry Point & Wiring ✅

The `orca` function that ties everything together.

| # | Task | Description | Status |
|---|---|---|---|
| 7.1 | CLI argument parsing | Parse `userPrompt` (positional) and `--verbose` via mainargs. Unit test: parse various arg combinations. | ✅ |
| 7.2 | `orca` context function | `def orca(...)(...: FlowContext ?=> Unit): Unit`. Creates FlowContext, registers listeners, runs the flow within an Ox scope. | ✅ |
| 7.3 | Top-level accessors | `def claude(using FlowContext)`, `def git(using FlowContext)`, etc. Verify they compile and resolve inside `orca:` blocks. | ✅ |
| 7.4 | FlowContext construction | Wire up: Claude backend, Codex backend (lazy), all tools, event dispatcher, interaction channel. Support custom overrides (`orca(git = ..., interaction = ...)`). | ✅ |
| 7.5 | Retry wiring | Wrap LLM calls with `ox.retry(schedule)`. Corrective retry on parse failure. Unit test: mock backend that fails twice then succeeds. | ✅ |
| 7.6 | scala-cli smoke test | Integration test: `sbt publishLocal`, then spawn `scala-cli run` with a minimal `.sc` script that uses the library. Verify exit code 0 and expected output. | ✅ |

**Exit criteria**: A minimal `.sc` script runs via `scala-cli` and calls `claude.ask("hello")`. End-to-end path works.

---

## Epic 8: Library Functions ✅

Higher-level flow combinators, built on all previous epics.

| # | Task | Description | Status |
|---|---|---|---|
| 8.1 | fixLoop | Implement the evaluate → fix → re-evaluate loop with max iterations and ignored issue tracking. Unit test with mock evaluate/fix functions. | ✅ |
| 8.2 | reviewAndFix | Parallel reviewers via `ox.par`, confidence filtering, optional lint, stage management. Uses fixLoop internally. Unit test with mock reviewers. | ✅ |
| 8.3 | lint | Run a shell command, capture output, summarize via LLM into `ReviewResult`. Unit test with a canned lint output. | ✅ |
| 8.4 | allReviewers/minimalReviewers | Pre-configured reviewer agents (performance, readability, test coverage). System prompts. | ✅ |
| 8.5 | Integration test | Full flow: code → lint → review → fix loop, with a mock backend. Verify event sequence and final ignored issues. | ✅ |

**Exit criteria**: fixLoop, reviewAndFix, lint pass unit tests. Integration test exercises the full loop.

---

## Epic 9: Codex Backend

Second backend. Mirrors the Claude architecture established in Epics 4
and 11 — `PipedCliProcess` subprocess, daemon reader thread producing
`ConversationEvent`s, `DefaultAgentCall` driving retries unchanged. The
plan's original WebSocket/app-server path may no longer be necessary:
if `codex exec --json` streams per-turn events and tool calls, we get
interactive support over the same stdio shape Claude uses, for free.
Task 9.1 decides.

| # | Task | Description | Status |
|---|---|---|---|
| 9.1 | Capability probe | Drive `codex exec --json --full-auto` with a tool-using, multi-turn prompt and inspect the JSONL stream. Does it emit partial text deltas, tool calls, and a mid-turn tool-approval channel — or only a batched final result? Answer determines whether 9.2 follows the Claude stdio pattern or falls back to app-server + WebSocket. Document findings in a short ADR. See [ADR 0007](adr/0007-codex-exec-jsonl-driver.md). | ✅ |
| 9.2 | Conversation driver | `CodexConversation` built on the same skeleton as `ClaudeConversation`: `PipedCliProcess`, daemon reader thread, `LinkedBlockingQueue[Option[ConversationEvent]]`, stderr drain thread, `outcomeRef` + `cancelled` gates, SIGINT on `cancel()`, `finalizeLoop` resolving the terminal outcome. Translate Codex's JSONL events (`thread.started`, `item.completed`, `turn.completed`, tool events) into `ConversationEvent.*`. Unit tests via `FakePipedCliProcess`. Only add a WebSocket + app-server path if 9.1 rules stdio out. | ✅ |
| 9.3 | Headless + interactive surface | `CodexBackend.runHeadless` / `continueHeadless` parse the final result out of the same JSONL stream (or use `--resume` for continuation). `runInteractive` / `continueInteractive` return `Conversation[Backend.Codex.type]` and accept both `prompt` (wire) and `displayPrompt` (renderer-facing), enqueuing a `ConversationEvent.UserMessage(displayPrompt)` at startup — contract established for Claude and already wired into `AgentBackend`. Tool approvals route through `ConversationEvent.ApproveTool(name, input, respond)` when the CLI exposes mid-turn approvals; otherwise `AgentConfig.autoApprove` is pre-baked into the spawn args. | ✅ |
| 9.4 | Config mapping | `AgentConfig.autoApprove` / `model` / `systemPrompt` → Codex CLI flags. Reuse `AgentConfig.defaultRetrySchedule` and `DefaultAgentCall`'s retry-with-corrective-prompt loop — no backend-specific retry logic. | ✅ |
| 9.5 | Prompt template | Verify `DefaultPromptTemplate` applies as-is. It now tells the agent to "deliver the final answer as a JSON-only message" and relies on `ResponseParser.parse` (fence-stripping + right-to-left balanced-`{...}` extraction + `MalformedAgentOutputException`). If Codex enforces a different structured-output convention, write a Codex-specific `PromptTemplate` rather than bending the shared one. | ✅ |
| 9.6 | Integration tests | Real `codex` calls gated on `ORCA_INTEGRATION`. Headless round-trip, `continueHeadless` resume, streaming deltas + `AssistantTurnEnd`, tool-approval denial (if supported). Mirror `ClaudeIntegrationTest`. | ✅ |

**Lessons from the Claude backend that apply verbatim:**

- **Don't pipe stderr for verbose-mode CLIs.** Route through `os.Inherit`
  or drain on its own thread — the 64KB pipe buffer fills silently and
  hangs the subprocess otherwise. `stderrLoop` in `ClaudeConversation`
  shows the drain-as-`ConversationEvent.Error` pattern when you *do*
  need stderr contents.
- **Close stdin after the opening turn** if `codex exec` batches stdin
  until EOF the way `claude -p --input-format stream-json` does. A
  lingering open stdin makes the subprocess wait forever for more input.
- **Never tell the agent to invoke a tool that isn't in its tool list.**
  Early Claude prompts instructed the model to use a fictional
  `structured-output` tool; it burned turns searching for it. The fix is
  post-hoc JSON extraction via `ResponseParser`, not a tool call.
- **Tolerate protocol drift.** Unknown top-level events → `Unknown(rawType)`
  (see `InboundMessage`). Tool-result content comes in multiple shapes
  (string literal / array of nested blocks) — capture as `RawJson` and
  decode at the display boundary rather than in the wire model.
- **Cancellation is SIGINT.** The reader thread sees EOF, `finalizeLoop`
  compare-and-sets `outcomeRef` to `Outcome.Cancelled`, the event queue
  closes. Don't force-close the queue from another thread.
- **`AgentConfig` val order.** `default` reads `defaultRetrySchedule`; Scala
  evaluates object vals in source order, so the schedule must appear
  first or the case-class default latches on null and downstream `retry`
  calls NPE. Already fixed in the shared `AgentConfig` but worth knowing
  when touching that file.

**UX parity checklist** (so swapping `claude` for `codex` in a flow
requires zero script changes):

- `ConversationEvent.UserMessage(displayPrompt)` enqueued at session start.
- `AssistantTextDelta` for every partial text chunk; `AssistantTurnEnd`
  at each assistant-message boundary so the terminal renderer can reset
  its prose-streaming state.
- `AssistantToolCall(name, rawInput)` for each tool invocation, with the
  input kept as raw JSON — `ToolInputSummary` pulls out the headline
  field at render time.
- `ToolResult(toolName, ok, content)` for each tool's return, matching
  Claude's shape (content is a single display string; the backend
  flattens nested-block content before enqueuing).
- `Error(message)` for non-fatal mid-session errors surfaced from stderr
  or protocol issues; fatal session-ending failures surface as
  `awaitResult()` exceptions.

**Exit criteria**: Codex backend passes unit tests with stubbed CLI.
Integration tests pass against a real `codex`. Swapping `claude` for
`codex` in `DefaultAgentCall.autonomous` / `continueSession` / `interactive`
paths requires no flow-script changes.

---

## Epic 10: Publishing & Documentation

Ship it.

| # | Task | Description | Status |
|---|---|---|---|
| 10.1 | sbt-ci-release setup | Configure sbt-ci-release or sbt-sonatype for Maven Central publishing. GPG signing, Sonatype credentials. | |
| 10.2 | Example scripts | Polished `.sc` files: development flow, planning flow. Placed in `examples/` and tested in CI. | |
| 10.3 | README | Project overview, quickstart, installation, link to design doc. | |
| 10.4 | End-to-end CI test | CI job that `publishLocal` → runs an example `.sc` with a mocked backend → verifies output. | |

**Exit criteria**: Library published to Maven Central. `//> using dep "org.virtuslab::orca:0.1"` works. README and examples exist.

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
  │     │     ↓
  │     │     └── Epic 11 (stream-json rewrite)
  │     │           ↓
  │     │           └── Epic 9 (Codex backend)  ← reuses stream-json infra
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

Epics 3, 4, 5, 6 can proceed in parallel after Epic 2. The original plan had Epic 9 (Codex) starting after Epic 3 in parallel with 4–8; after Epic 11 finished we chose to let Codex reuse the stream-json `PipedCliProcess` + `Conversation` + driver infrastructure instead of rebuilding it on sttp WebSockets, so Epic 9 now sequences after Epic 11. The minimum viable flow — 1 → 2 → 3 → 4 → 5 → 7, skipping 6 for terminal and 9 for Codex — is unchanged.

---

## Epic 11: Stream-json rewrite of the interactive path ✅

Follow-up to Epics 3 & 4. Replaces the `<<<ORCA_DONE>>>` marker + stop-hook + sentinel-file mechanism with a stream-json controlled subprocess and a typed `Conversation` API. See [`adr/0006-stream-json-conversation-driver.md`](adr/0006-stream-json-conversation-driver.md).

| # | Item | Description | Status |
|---|---|---|---|
| 11.1 | Wire-protocol model | `orca.tools.claude.streamjson` package: InboundMessage, ContentBlock, StreamEventPayload, ControlRequestBody, OutboundMessage with jsoniter codecs. Unknown/Unhandled variants for forward-compat. | ✅ |
| 11.2 | Bidirectional CliProcess | `PipedCliProcess` with `writeLine`, `stdoutLines` / `stderrLines` iterators, `tryExitCode`, `closeStdin`. `FakePipedCliProcess` for tests. | ✅ |
| 11.3 | Conversation contract | `Conversation[B]` (events + awaitResult + sendUserMessage + cancel), `ConversationEvent` enum, `ApprovalDecision`, `OrcaInteractiveCancelled`. `Interaction.drive(conversation)` replaces `runInteractive(handle)`. | ✅ |
| 11.4 | ClaudeConversation driver | Reader thread, event queue, autoapprove gating, per-message translation, cancel via SIGINT. | ✅ |
| 11.5 | Backend rewrite | `ClaudeBackend.runInteractive` / `continueInteractive` return `Conversation[B]`. `ClaudeArgs.streamJson` replaces the old `interactive`. `DefaultAgentCall.interactive` delegates to `interaction.drive`. | ✅ |
| 11.6 | Prompt template | Drop `<<<ORCA_DONE>>>` from `DefaultPromptTemplate.interactive`; thread `--json-schema` via `outputSchema: Option[String]`. | ✅ |
| 11.7 | Stop-hook cleanup | Delete `ClaudeStopHook`, `ClaudeInteractiveHandle`, `DoneMarkerExtractor`, `InteractiveHandle` trait, the inherited-stdio `spawn` path, `prepareWorkspace` on the backend trait. | ✅ |
| 11.8 | TerminalConversationRenderer | Per-event rendering (streaming text, tool calls, tool results, errors), spinner coordination, approval prompts via a `Prompter` seam. | ✅ |
| 11.9 | Integration tests | Real-CLI tests: headless round-trips, streaming deltas + turn-end, tool-approval denial. Gated on `ORCA_INTEGRATION`. | ✅ |
| 11.10 | Documentation | ADR 0006; design.md rewrite of the interactive section; ADR 0003 updated to reflect the new backend surface. | ✅ |

**Exit criteria**: no in-band markers; `--json-schema` drives structured validation; the terminal renders live streaming turns and prompts for tool approvals; 175+ tests green.

---

## Epic 12: Stream-json follow-ups

Items deferred during the Epic 11 reviews. Independent, order-agnostic, each ~half-a-day or less unless noted.

| # | Item | Description | Status |
|---|---|---|---|
| 12.1 | `ConversationEvent.Usage` | Emit an event as `result.usage` arrives (and, if upstream ever adds it, mid-session usage deltas) so Slack/HTTP channels can show live cost/token readouts. `DefaultAgentCall` keeps translating to `OrcaEvent.TokensUsed` for `CostTracker`. | |
| 12.2 | Ctrl-C-during-streaming → graceful cancel | Today Ctrl-C kills the JVM when no readline prompt is active; only approval prompts cancel gracefully (via `UserInterruptException`). Install a `Signal("INT")` handler around `TerminalConversationRenderer.render` that calls `conversation.cancel()` instead of exiting, then removes itself on return. | |
| 12.3 | Real-subprocess cancel integration test | `ClaudeConversation.cancel()` is unit-tested against `FakePipedCliProcess` only. Add a gated integration case that spawns real `claude` with a long-running prompt, calls `cancel`, asserts `awaitResult` throws `OrcaInteractiveCancelled` within a timeout, and the subprocess exits. | |
| 12.4 | Interactive-path schema-threading test | `DefaultAgentCall.interactive` generates `JsonSchemaGen[O]` and forwards it as `Some(schema)` to `backend.runInteractive`. No unit test pins this; add one that captures `outputSchema` on a stub `AgentBackend.runInteractive` and asserts the schema is present + shaped for the case-class `O`. | |
| 12.5 | `writeOutbound` I/O failures fail the session | `ClaudeConversation.handleControlRequest`'s stdin writes can throw `IOException` if the child died; today that surfaces as a `ConversationEvent.Error` and the reader keeps polling. Let the exception propagate so the reader's `NonFatal` catch records `Outcome.Failed` and `awaitResult` rethrows. | |
| 12.6 | `ClaudeConversation` safe-publication factory | The reader thread starts in the constructor — safe in practice given final-field + `Thread.start` happens-before, not structurally enforced. Split into a private constructor + `object ClaudeConversation.open(process, config)` that constructs then starts the thread. | |
| 12.7 | Reader-thread join timeout on `awaitResult` | If the child ignores SIGINT (hung GC, attached debugger), the reader leaks forever. `readerThread.join(timeout)` with a sensible default (30s?) + a `Failed(OrcaFlowException("reader did not terminate"))` path. | |
| 12.8 | ACP client adapter | N-backend portability play. Once Codex work (Epic 9) lands, wrap `AgentBackend` dispatch behind an Agent Client Protocol JSON-RPC client: one Scala client covers Claude/Codex/Gemini/Copilot CLIs (native or via sidecar adapters). See the tradeoffs discussion in ADR 0006's "Alternatives" section. Bigger task — budget 3–5 days. | |
| 12.9 | `ConversationEvent.Error` at WARN, not ERROR | Unknown `InboundMessage` types currently surface as `Error` events; semantically they're "protocol drift, not actionable". Add a `Warning` variant (or a severity field) so the channel can render them at a lower level. | |

**Exit criteria**: none as a gate — each item stands alone. 12.2 and 12.8 are the highest-user-facing impact; 12.5 and 12.7 are the highest-reliability impact.
