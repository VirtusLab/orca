# 0007. Drive Codex via `codex exec --json` stdio JSONL

Status: Accepted · Date: 2026-04-24
Amends: [ADR 0003](0003-pluggable-llm-backends.md) (backend surface)
Related: [ADR 0006](0006-stream-json-conversation-driver.md) (Claude stream-json driver)

## Context

Epic 9's original plan assumed Codex would need a WebSocket app-server
(`codex app-server`) because `codex exec` was thought to be a
batched one-shot. The decision gate for Epic 9.1 was to probe
`codex exec --json` with a multi-turn, tool-using prompt and see
whether the JSONL stream is rich enough to drive an interactive
session the way Claude's stream-json mode does — or whether the
WebSocket fallback is required.

This ADR records the probe results (codex-cli 0.125.0) and the
resulting architectural choice.

## Probe findings

`codex exec --json` emits one JSON object per line on stdout,
with these event types:

| `type`            | Payload                                                               | Notes |
| ----------------- | --------------------------------------------------------------------- | ----- |
| `thread.started`  | `thread_id`                                                           | First event; this is our `SessionId`. |
| `turn.started`    | —                                                                     | Turn boundary. |
| `item.started`    | `item: { id, type, … }`                                               | Precedes a tool invocation. |
| `item.completed`  | `item: { id, type, … }` (populated with result fields)                | Agent message, tool result, or file-change completion. |
| `turn.completed`  | `usage: { input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens }` | Terminal event for the turn. |

Observed item types (`item.type`):

- `agent_message` — `{ id, text }`. Assistant prose.
- `command_execution` — `{ id, command, aggregated_output, exit_code, status }`. Shell tool call + its output.
- `file_change` — `{ id, changes: [{ path, kind }], status }`. File edits.
- `reasoning`, `mcp_tool_call`, `web_search`, `todo_list` — referenced in the binary; not exercised in the probe but documented for forward-compat.

Key differences from Claude's stream-json:

1. **No partial text deltas.** Agent prose arrives as a whole
   `item.completed` with `type: "agent_message"`. There is no
   block-delta stream; `--include-partial-messages` has no
   equivalent on `codex exec`.
2. **No mid-turn approval subchannel.** `-c approval_policy=on-request`
   silently executes (or blocks) commands without emitting anything
   to stdout. Tool approvals are not negotiated over the JSONL
   stream — they're baked into the CLI spawn via `--full-auto`,
   `--sandbox <mode>`, or `--dangerously-bypass-approvals-and-sandbox`.
3. **Stdin is consumed once.** codex reads the initial prompt from
   argv and optionally appends stdin as a `<stdin>` block; it does
   **not** accept subsequent user messages over stdin the way
   `claude --print --input-format stream-json` does (after we close
   stdin anyway). Multi-turn requires `codex exec resume <thread_id>`,
   which spawns a fresh process.
4. **Session resume works** via `codex exec resume <thread_id>`,
   provided the previous session was not `--ephemeral`.
5. **`--output-schema <file>`** constrains the final `agent_message`
   to a JSON-Schema-validated payload — the codex equivalent of
   Claude's `--json-schema`.

Example stream (file-edit scenario):

```json
{"type":"thread.started","thread_id":"019dc0e7-f5d2-..."}
{"type":"turn.started"}
{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I'll update hello.txt."}}
{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"…","status":"in_progress"}}
{"type":"item.completed","item":{"id":"item_1","type":"command_execution","aggregated_output":"hello\n","exit_code":0,"status":"completed"}}
{"type":"item.started","item":{"id":"item_2","type":"file_change","changes":[{"path":"…/hello.txt","kind":"update"}],"status":"in_progress"}}
{"type":"item.completed","item":{"id":"item_2","type":"file_change","status":"completed"}}
{"type":"item.completed","item":{"id":"item_3","type":"agent_message","text":"Updated hello.txt to say 'goodbye'."}}
{"type":"turn.completed","usage":{…}}
```

## Decision

Drive Codex via `codex exec --json` over stdio. Reuse the
`PipedCliProcess`/`Conversation[B]` infrastructure built for Claude
(Epic 11). Do **not** introduce a WebSocket app-server path.

Concretely:

- `CodexBackend.runHeadless` / `continueHeadless` spawn `codex exec`
  (or `codex exec resume <id>`) with `--json`, consume the JSONL
  stream, and extract `thread_id` + final `agent_message` text +
  `usage` from `turn.completed` into an `LlmResult`. Note the
  divergence from ADR 0006's claude shape: claude emits an explicit
  terminal `result` message that carries `output` / `structured_output`
  fields the driver reads directly. Codex has no such terminal
  message — its `turn.completed` only carries usage. The driver
  therefore *synthesises* `LlmResult.output` by snapshotting the
  text of the last `item.completed` of type `agent_message` seen
  before `turn.completed`. The prompt template instructs the agent
  to make that final message JSON-only, so the snapshot is the
  structured payload in practice; in failure modes (the agent emits
  prose after its JSON, or skips JSON entirely) the post-hoc
  `ResponseParser` catches it via the corrective-retry loop.
- `CodexBackend.runInteractive` / `continueInteractive` spawn the
  same process and wrap it in `CodexConversation`, which mirrors
  `ClaudeConversation` structurally (daemon reader, event queue,
  stderr drain, `outcomeRef` + `cancelled` gates, SIGINT on cancel,
  `finalizeLoop`).
- `CodexConversation` translates JSONL events:
  - `thread.started` → stash `thread_id` (no event).
  - `item.completed` with `type: "agent_message"` → one
    `AssistantTextDelta(text)` followed by `AssistantTurnEnd`.
    Since codex doesn't stream deltas, each message is a single
    "delta".
  - `item.started` with `type: "command_execution"` →
    `AssistantToolCall(name="bash", rawInput=<json with command>)`.
  - `item.completed` with `type: "command_execution"` →
    `ToolResult(toolName="bash", ok=(exit_code==0), content=aggregated_output)`.
  - `item.{started,completed}` with `type: "file_change"` →
    `AssistantToolCall(name="file_change", …)` / `ToolResult(…)`.
  - `turn.completed` → record usage; finalize `LlmResult`.
  - Unknown item types → dropped (forward-compat).

Consequences of the JSONL limitations:

- **`sendUserMessage` is a no-op** (same caveat as Claude's current
  single-shot stream-json path). Multi-turn interactive requires
  `continueInteractive(sessionId, …)` which spawns a fresh `codex
  exec resume`.
- **`ApproveTool` events are not emitted.** `LlmConfig.autoApprove`
  is pre-baked into the spawn args:
  - `AutoApprove.All` → `--dangerously-bypass-approvals-and-sandbox`
    (or `--full-auto` if the caller prefers a sandbox).
  - `AutoApprove.Only(_)` → `--full-auto` (codex doesn't support a
    per-tool allowlist at the CLI; the narrower allowlist semantic
    is approximated by the sandbox).
- **Agent-message "streaming" is chunky.** The renderer will emit
  whole messages at once. Acceptable — the terminal UX already
  handles per-line delivery.

## Why not the WebSocket / app-server path

- The JSONL stream already covers every event the `Conversation`
  contract needs: tool calls, tool results, assistant prose,
  usage, session id, errors.
- `codex app-server` is marked experimental; the exec path is the
  documented public interface.
- Adding a WebSocket client means dragging sttp into the codex
  module (build.sbt already has `sttpClient` / `sttpClientOx` there
  speculatively — to be removed in 9.3), a second JSON schema,
  handshake/auth code, and a test double for a WebSocket
  connection. None of which earns capability the JSONL path lacks.
- If codex later gains mid-turn approvals over stdin or partial
  message deltas, those arrive as additive events in the same
  JSONL stream; the driver picks them up without rearchitecting.

## Alternatives considered

- **WebSocket via `codex app-server`.** See above. Rejected.
- **`codex mcp-server`.** Codex as an MCP server is for
  *exposing* codex's tools to external agents, not for driving
  codex. Wrong direction.
- **Pure `codex exec` without `--json`.** Would force transcript
  scraping for tool calls and session id — the same failure mode
  Claude's old `<<<ORCA_DONE>>>` path hit. Rejected.

## Testing

- `CodexConversationTest` will pin the driver against a
  `FakePipedCliProcess` with scripted JSONL: each event type maps
  to the expected `ConversationEvent` set, cancel propagates
  through `awaitResult`, `is_error`-style failures surface as
  `Outcome.Failed`.
- `CodexIntegrationTest` (gated on `ORCA_INTEGRATION=1`) will
  exercise real `codex exec`: headless round-trip, `exec resume`,
  and a session with a tool call that fires `AssistantToolCall` +
  `ToolResult`.
