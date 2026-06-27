# 0006. Drive Claude Code via stream-json instead of TTY handoff

Status: Accepted · Date: 2026-04-23
Amends: [ADR 0003](0003-pluggable-agent-backends.md) (backend surface)
Related: [ADR 0002](0002-context-function-flow-dsl.md) (flow DSL)

## Context

Before this ADR, Orca's interactive Claude path spawned `claude` with
inherited stdio so the user's terminal was claude's terminal. Claude
was instructed by prompt to emit a `<<<ORCA_DONE>>>` marker followed by
JSON when done; a user-installed `.claude/settings.json` Stop hook
watched the transcript for that marker and wrote the JSON payload to a
filesystem sentinel (`/tmp/orca-<session-id>.json`); Orca polled the
sentinel, sent SIGINT, and read the result. That path worked but
coupled Orca to the claude CLI's specifics, prevented non-terminal
interaction channels (Slack / HTTP), gave us no say over tool
approvals, and was fragile in several dimensions (transcript races,
sentinel-file portability, SIGINT timing).

## Decision

Orca's interactive path to Claude Code uses a stream-json controlled
subprocess, not an inherited-TTY handoff. The backend spawns
`claude --print --input-format stream-json --output-format stream-json
--verbose --include-partial-messages` with pipes on all three stdio
handles, writes the initial user turn as NDJSON, and wraps the
subprocess in a `ClaudeConversation` that a channel's
`Interaction.drive` consumes event-by-event.

The flag set is deliberate: `--print` is the mode that accepts
`--input-format stream-json`; `--verbose` surfaces the tool-use and
`result` messages we need; `--include-partial-messages` lets us stream
text deltas instead of waiting for whole turns.

## Shape

```
DefaultAgentCall.interactive
   └─ backend.runInteractive(prompt, config, workDir, schema)
         └─ spawnPiped → ClaudeConversation
                ├─ reader thread
                │     parses NDJSON InboundMessage
                │     → enqueues ConversationEvent
                │     (AssistantTextDelta, ToolCall, ApproveTool, …)
                └─ Conversation surface
                      ├─ events: Iterator[ConversationEvent]
                      ├─ sendUserMessage(text)
                      ├─ cancel()
                      └─ awaitResult(): AgentResult[B]
   └─ interaction.drive(conversation)
         └─ TerminalConversationRenderer
               renders events; prompts for tool approvals
               via JLine; returns AgentResult on success or
               throws OrcaInteractiveCancelled on cancel
```

Key shifts vs. the previous TTY path:

- **No `<<<ORCA_DONE>>>` marker.** The final `result` message from
  claude carries the session id, usage, and (when `--json-schema` is
  passed) the validated `structured_output`. No transcript scraping.
- **No stop hook files in `.claude/`.** The workspace is left
  untouched; `prepareWorkspace` retired off the `AgentBackend` trait.
- **The terminal is Orca's, not claude's.** Orca renders turns,
  streams text, displays tool calls, prompts for approvals, and
  decides what to show. The backend never inherits stdio.
- **Approvals go through `ApproveTool` events**, each carrying a
  `respond: ApprovalDecision => Unit` closure the channel invokes
  exactly once. The driver auto-approves tools that match
  `AgentConfig.autoApprove` before the event would fire; only
  channel-level decisions surface as events.
- **Cancellation surfaces as `Either`**: `Conversation.awaitResult`
  returns `Either[OrcaInteractiveCancelled, AgentResult[B]]`. Genuine
  subprocess failures still throw, since they aren't recoverable, but
  user cancels are now in the type — direct callers can't accidentally
  ignore them. The `Interaction.drive` boundary is where the Either
  becomes an exception (`throw cancelled`) for stage-level propagation,
  so flow scripts continue to see exception-shaped cancellation that
  the existing `stage(...)` machinery handles.

## Why

- **Control.** Scraping a transcript for a marker means everything
  claude-specific. Stream-json gives us typed messages, tool
  approvals as a first-class subchannel, and `--json-schema` for
  validated output — all portable primitives we own.
- **Channel-agnosticism.** `Interaction.drive(Conversation[B])` is
  a protocol the terminal implements today and Slack/HTTP can
  implement tomorrow. The previous TTY handoff only worked where
  "the terminal" existed.
- **Deletability of fragile code.** The marker + sentinel file +
  SIGINT-at-the-right-moment dance was all compensation for the
  agent not telling us when it was done. Stream-json tells us. The
  compensation code comes off.
- **Forward compatibility.** When second-backend work starts
  (codex, gemini, …), the `Conversation` shape is already shared;
  the per-backend wiring is just "parse this backend's stdout and
  translate to ConversationEvent". Or eventually: wrap in ACP (see
  alternatives).

## Consequences

- `ClaudeBackend.runInteractive` returns `Conversation[B]` instead
  of `InteractiveHandle[B]`. `InteractiveHandle`,
  `ClaudeInteractiveHandle`, `ClaudeStopHook[Test]`,
  `DoneMarker(Extractor)`, and `Interaction.runInteractive(handle)`
  are all gone.
- `CliRunner.spawn` (inherited-TTY) is gone; only `spawnPiped`
  remains.
- The default prompt for interactive calls changed — no marker, no
  "emit `<<<ORCA_DONE>>>`" sentinel. Custom `Prompts` impls that
  relied on that convention need updating.
- `AgentBackend.runInteractive` / `continueInteractive` require an
  explicit `outputSchema: Option[String]` argument. Callers pass the
  JSON Schema they expect the final turn to match; the backend
  forwards to `--json-schema`. `None` is legal for free-form text.

## Alternatives considered

- **Claude Agent SDK (TypeScript/Python).** Rich programmatic API
  (`canUseTool` callback, hook taxonomy, in-process MCP). Would
  force a Node or Python sidecar inside an otherwise pure-Scala/Ox
  system, adds an IPC hop, and still doesn't give us portability to
  other backends. Consulted the SDK docs for shapes; didn't adopt.
- **Agent Client Protocol (ACP).** JSON-RPC 2.0 over stdio; same
  transport family as LSP. Adopted by Zed, OpenCode, JetBrains. The
  right long-term target for N-backend portability, but Claude Code
  itself doesn't speak ACP natively — the existing adapters wrap
  the Agent SDK in TypeScript, bringing the Node-sidecar problem.
  Adopting now means writing the stream-json layer anyway and
  adding a JSON-RPC protocol layer on top. Deferred to a later
  migration: once stream-json is proven, add an ACP client and
  switch backend dispatch at the `AgentBackend` seam.
- **Keep TTY handoff.** The fragile part. Blocks channel
  abstraction — Slack/HTTP can't inherit a terminal. Rejected.

## Testing

- `ClaudeConversationTest` pins the driver against a
  `FakePipedCliProcess` with scripted NDJSON: every
  `InboundMessage` variant maps to the expected
  `ConversationEvent` set, autoapprove policy fires silently,
  cancel propagates through `awaitResult`, multiple pending
  `ApproveTool`s are distinguishable by their respond closures.
- `TerminalConversationRendererTest` pins per-event rendering and
  the approval-prompt path via a `Prompter` stub.
- `ClaudeIntegrationTest` (gated on `ORCA_INTEGRATION=1`) exercises
  the real CLI: headless round-trips, a streaming session that
  verifies AssistantTextDelta + AssistantTurnEnd land, and a
  tool-approval scenario that denies a `Read` request via the
  `respond` closure.
