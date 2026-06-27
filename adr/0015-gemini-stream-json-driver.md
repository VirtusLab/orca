# 0015. Drive Gemini CLI via `gemini --output-format stream-json` stdio JSONL

Status: Accepted · Date: 2026-06-01
Amends: [ADR 0003](0003-pluggable-llm-backends.md) (backend surface)
Related: [ADR 0006](0006-stream-json-conversation-driver.md) (Claude stream-json driver), [ADR 0007](0007-codex-exec-jsonl-driver.md) (Codex exec JSONL driver), [ADR 0012](0012-mcp-host-bridge.md) (ask_user MCP bridge), [ADR 0014](0014-opencode-server-driver.md) (OpenCode server driver)

## Context

Orca already ships two LLM backends — Claude (bidirectional stream-json,
ADR 0006) and Codex (one-shot `exec --json`, ADR 0007). Adding Google's
`gemini` CLI as a third backend follows the established `AgentBackend[B]`
SPI. The open question was which of gemini's headless output shapes to
drive and how closely it maps to the existing infrastructure.

Gemini's headless mode (triggered by `-p/--prompt` or a non-TTY) offers
three output formats via `--output-format`: `text`, `json` (a single
`{response, stats, error}` blob), and `stream-json` (newline-delimited
JSONL events). It maps remarkably closely to the Codex shape: one-shot
per invocation, multi-turn via a resume flag, no `--append-system-prompt`
equivalent (it reads `GEMINI.md` for static instructions).

## Findings

`gemini -p <prompt> --output-format stream-json` emits one JSON object
per line on stdout, with these event types:

| `type`        | Payload (fields the driver reads)                       | Notes |
| ------------- | ------------------------------------------------------- | ----- |
| `init`        | `session_id`, `model`                                   | First event; `session_id` drives `--resume`. |
| `message`     | `role`, `content`                                       | User (prompt echo) or assistant message chunk. |
| `tool_use`    | `tool_name`, `tool_id`, `parameters`                    | Tool call request. |
| `tool_result` | `tool_id`, `status`, `output`                           | Result of an executed tool. |
| `error`       | `message`                                               | Non-fatal warning / system error. |
| `result`      | `status`, `stats: { input_tokens, output_tokens, … }`  | Terminal event; carries aggregated usage. |

Key differences from the existing backends:

1. **No single terminal payload.** Unlike Claude's terminal `result`
   message (which carries `output`/`structured_output`), gemini's
   `result` event carries only status + stats. Like Codex, the driver
   **synthesises** `AgentResult.output` — but where Codex snapshots the
   *last* `agent_message`, gemini streams assistant prose as `message`
   chunks, so the driver *accumulates* the content of every non-`user`
   message and reads the buffer at the `result` event.
2. **Assistant role is version-dependent.** Gemini's API uses the
   `model` role; the CLI may spell it `model` or `assistant`. The driver
   treats any role *other than* `user` as agent output, rather than
   matching a fixed string.
3. **No mid-turn approval subchannel.** Tool approvals are baked into the
   spawn via `--approval-mode`. In headless mode `default` and
   `auto_edit` would block on an approval prompt no one can answer, so
   the only workable non-read-only mapping is `yolo`.
4. **No `--output-schema` flag.** Gemini has no native structured-output
   gate. `resultAs[O]` enforcement is prompt-template + the post-hoc
   `DefaultAgentCall` corrective-retry loop only; `outputSchema` is still
   threaded to `GeminiConversation` so the autonomous drain suppresses
   the raw JSON payload from the user log.
5. **Session resume** works via `gemini --resume <session-id> -p …`,
   where the id is the one surfaced on the prior run's `init` event.
6. **MCP servers are file-configured, not flag-configured.** Gemini reads
   MCP servers only from `settings.json` (no inline `-c` override like
   Codex).

Example stream (tool-using scenario):

```json
{"type":"init","session_id":"019dc0e7-…","model":"gemini-2.5-pro"}
{"type":"message","role":"assistant","content":"I'll list the files."}
{"type":"tool_use","tool_name":"Bash","tool_id":"b1","parameters":{"command":"ls"}}
{"type":"tool_result","tool_id":"b1","status":"success","output":"hello.txt\n"}
{"type":"message","role":"assistant","content":"There is one file: hello.txt."}
{"type":"result","status":"success","stats":{"input_tokens":120,"output_tokens":18}}
```

## Decision

Drive Gemini via `gemini --output-format stream-json` over stdio. Reuse
the `StreamConversation`/`Conversation[B]` infrastructure (the same base
Claude and Codex sit on). Do **not** use the single-`json` blob (loses the
live event stream) or any experimental app-server.

Concretely:

- `GeminiArgs.headless` spawns `gemini --skip-trust -p <prompt>
  --output-format stream-json [-m <model>] [--approval-mode …]`;
  `GeminiArgs.resume` adds `--resume <session-id>`. cwd is set on the OS
  process spawn (gemini headless has no `-C` flag), so it isn't rendered
  into argv.
- **`--skip-trust` is unconditional.** Gemini refuses to run headless in a
  folder it doesn't consider "trusted" (exit 55) and silently overrides
  `--approval-mode` back to `default` before failing. orca always drives a
  working directory the agent is meant to operate in, so trust is
  unconditional — the direct analog of codex's `--skip-git-repo-check`
  (equivalent to `GEMINI_CLI_TRUST_WORKSPACE=true`). Found via the gated
  integration run.
- `GeminiBackend` keeps a `SessionRegistry.ClientToServer` mapping the
  caller's client id to gemini's `init`-reported session id (gemini mints
  its own), committing the mapping post-drain so a follow-up call resumes
  via `--resume`. Identical scheme to Codex.
- `GeminiConversation` translates JSONL events:
  - `init` → stash `session_id` + `model` (no event).
  - `message` with `role != "user"` → one `AssistantTextDelta(content)`,
    appended to the answer buffer. `role == "user"` (prompt echo) is
    dropped — the base already surfaced the opening prompt.
  - `tool_use` → `AssistantToolCall(tool_name, parameters)`; the
    `tool_id → tool_name` map lets the later `tool_result` (which carries
    only the id) be keyed by name.
  - `tool_result` → `ToolResult(name, ok = status == "success", output)`.
  - `error` → `ConversationEvent.Error`.
  - `result` → emit `AssistantTurnEnd`, then finalise `AgentResult` with the
    accumulated answer + usage.
  - Unknown top-level types → dropped (forward-compat).
- **System prompt fold.** Gemini has no `--append-system-prompt`, so the
  composed system prompt (+ optional `ask_user` hint) is folded into the
  user prompt as a `"System guidance:"` preamble — identical to Codex.
- **Approval mapping** (`GeminiArgs`):
  - `readOnly = true` → `--approval-mode plan`
  - `AutoApprove.All` → `--approval-mode yolo`
  - `AutoApprove.Only(_)` → `--approval-mode yolo` (widened — see below)
- **Models.** `GeminiAgent.flash` pins `gemini-2.5-flash`; bare `gemini`
  pins `gemini-2.5-pro` in the runtime wiring (mirroring claude's Opus
  default for the long-lived implementer). Model literals may rename in
  future CLI versions; override via `withConfig`.

### The `AutoApprove.Only` widening

Gemini has no per-tool allowlist on the CLI, and in headless mode
`auto_edit` blocks on shell-tool approvals that no one can answer. So
`AutoApprove.Only(tools)` is mapped to `yolo` — "auto-approve a known set"
widens to "auto-approve all". This is less restrictive than the caller
asked for; it is the pragmatic analog of Codex's `--full-auto`
approximation, and it keeps autonomous turns from deadlocking.

### The `ask_user` MCP bridge via `.gemini/settings.json`

Interactive calls stand up the shared `AskUserMcpServer` on an ephemeral
port (ADR 0012). Because gemini reads MCP servers only from
`settings.json`, `GeminiSettings.register` merges an
`mcpServers.orca.httpUrl` entry into a project-local
`<workDir>/.gemini/settings.json` before the spawn and restores the prior
state afterward. The restore rides as an `extras` `AutoCloseable` on the
`AskUserSession`, so the base layer runs it when the conversation
finalises. The merge preserves unknown top-level keys and other configured
servers (held as `RawJson`), and only touches `allowedMcpServerNames` when
it already exists — introducing an allowlist where there was none would
restrict gemini to *only* orca and hide the user's other servers.

Two sharp edges, accepted:

- **Concurrency.** Two interactive runs in the same `workDir` race on the
  settings file. Flows already mint a fresh session per concurrent
  reviewer, but two *interactive* runs in one directory is unsupported.
- **Crash safety.** A hard crash skips the restore, leaving a stale `orca`
  entry. Restore is best-effort, not transactional.

## Why not the single `json` blob or an app-server

- `--output-format json` returns one object at exit — no live
  `ToolUse`/`AssistantMessage` events while the agent works, and a worse
  fit for the `Conversation` contract every other backend implements.
- The JSONL stream already covers every event the contract needs (tool
  calls, results, assistant prose, usage, session id, errors), and future
  gemini event types arrive additively in the same stream.

## Alternatives considered

- **`gemini mcp add` / `gemini mcp remove`** to register the ask_user
  server. Rejected: mutates the user's *global* config with stateful side
  effects; the project-local merge+restore is narrower and reversible.
- **A throwaway config-dir override.** Cleaner isolation, but depends on a
  config-home flag/env we couldn't confirm across versions. Deferred; the
  project-local merge is the documented path today.

## Testing

- `GeminiArgsTest` — flag mapping (model, approval modes, resume).
- `jsonl.InboundEventTest` — each event type parses defensively; unknown
  types collapse to `Unknown`; missing fields default.
- `GeminiConversationTest` — scripted JSONL against a
  `FakePipedCliProcess`: answer accumulation, user-echo drop, tool
  round-trip, ask_user suppression, cancel, clean-exit-without-result.
- `GeminiSettingsTest` — merge preserves keys, allowlist rule, exact-byte
  restore, file removal when none existed.
- `GeminiBackendTest` — session id / usage extraction, resume dispatch,
  systemPrompt fold, MCP registration on interactive (autonomous skips it).
- `DefaultGeminiAgentTest` — `flash`/pro model pins reach `--model`.
- `GeminiIntegrationTest` (gated on `ORCA_INTEGRATION`) — real `gemini`:
  headless round-trip and a resumed turn that recalls prior context.
