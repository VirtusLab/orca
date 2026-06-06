# 14. OpenCode server driver

Date: 2026-06-04

## Status

Proposed — implementation plan.

## Context

We want OpenCode (`sst/opencode`, binary `opencode`, v1.15.x) as a third `LlmTool`
backend alongside Claude and Codex. The existing two backends are **CLI drivers**:
each turn spawns a one-shot subprocess (`claude --print` stream-json, ADR 0006;
`codex exec --json` JSONL, ADR 0007) and parses its stdout.

OpenCode does not fit that mould. Its non-interactive CLI (`opencode run --format
json`) emits NDJSON events but exposes **no structured-output flag** — native
JSON-schema enforcement (the thing both other backends rely on for `resultAs[O]`)
is only reachable through the **headless HTTP server** (`opencode serve`). Since
structured output is core to orca, we drive the server, not the CLI.

### Server API (empirically verified against v1.15.13 `/doc` OpenAPI 3.1 spec)

`opencode serve --port <n>` starts a long-lived HTTP server (stdout: `opencode
server listening on http://127.0.0.1:<port>`). Relevant surface:

| Concern | Endpoint / shape |
|---|---|
| Create session | `POST /session` → `{ id: "ses_…", … }` (server-allocated id) |
| Send a turn (**blocking**) | `POST /session/{sessionID}/message` → `{ info: AssistantMessage, parts: Part[] }` |
| Send a turn (async) | `POST /session/{sessionID}/prompt_async` → `204` |
| Abort a turn | `POST /session/{sessionID}/abort` |
| Event stream (SSE) | `GET /event` → `text/event-stream`, `server.connected` then bus events |
| Ask-user reply | `POST /question/{requestID}/reply`, `POST /question/{requestID}/reject` |
| Permission reply | `POST /permission/{requestID}/reply` |

`POST …/message` body: `{ parts: [{type:"text", text}], model: {providerID,
modelID}, agent?, system?, variant?, tools?, format? }`.

`format` = `OutputFormatJsonSchema`: `{ type:"json_schema", schema:<JSONSchema>,
retryCount?:int }`. The server injects a `StructuredOutput` tool to force
compliance and returns the validated object on `AssistantMessage.structured`.

`AssistantMessage` carries: `structured` (the typed payload), `tokens`
`{input,output,reasoning,cache:{read,write}}`, `cost`, `finish`, `modelID`,
`providerID`, `sessionID`, `error`.

Bus event types (subset, from `Event*` schemas): `session.created`,
`message.updated`, `message.part.updated`/`.delta`/`.removed`, `permission.asked`,
`permission.replied`, `question.asked` (`QuestionRequest{ id, sessionID,
questions:[QuestionInfo], tool? }`), `question.replied`/`.rejected`,
`session.idle`, `session.error`, plus `session.next.*` and `server.heartbeat`.
Session-scoped events carry `properties.sessionID`; server-level ones
(`server.connected`, `server.heartbeat`) do not (see *Event stream*).

### Two consequences that distinguish OpenCode from Claude/Codex

1. **The result lives in the event stream.** The final `message.updated` carries
   the validated `structured` payload + `tokens`, so a turn is started with
   `prompt_async` and its `LlmResult` is read from the SSE stream — the same
   single-stream shape Codex gets from `turn.completed`, so the existing
   `StreamConversation` machinery applies.
2. **`ask_user` is native** (`question.asked` event + `/question/{id}/reply`). The
   `AskUserMcpServer` host bridge (ADR 0012) that Claude and Codex need is **not
   required** for OpenCode. This removes the MCP/Netty machinery from the
   interactive path.

### Server vs. CLI ownership

Claude/Codex spawn one process **per turn**. The OpenCode server is **one
long-lived process shared by all sessions and tools**; each *conversation* opens
its own SSE stream against it. The server is owned by the backend instance and torn
down on Ox-scope close.

**Session ids: the client→server mapping is required, not optional.** We *cannot*
use our own ids: `POST /session` has no `id` field (verified — server always mints
`ses_…`). And even though the autonomous path could carry the server id back, the
framework's **interactive** path pins the caller's `SessionId` and reconciles via
`backend.registerSession(client, server)` (`DefaultLlmCall` returns the caller's
`session`, not `result.sessionId`) — so the mapping lives at the framework level
regardless. Use `SessionRegistry.ClientToServer` + the `registerSession` override,
exactly like Codex; return `result.copy(sessionId = session)` to keep the caller's
handle stable.

## Decision

Add an `opencode` module with an `OpencodeBackend` that drives a shared
`opencode serve` over HTTP+SSE. Each turn starts with `POST …/prompt_async` and
reads its **own** `GET /event` SSE stream as the single source — translating bus
events to `ConversationEvent`s and deriving the `LlmResult` from `message.updated`
(native `format` gives structured output on `info.structured`) — by **reusing the
existing `StreamConversation` machinery**. `ask_user` is answered natively (no MCP
bridge).

## Module & file layout

New module `opencode` (mirrors `codex`), `.dependsOn(tools, tools % "test->test")`,
wired into `runner`'s `.dependsOn(...)`. REST + SSE go over the JDK `java.net.http`
client (no new dependency) behind a testable `OpencodeHttp` trait; JSON via jsoniter
(already used). The client is pinned to **HTTP/1.1**: the server hangs on the HTTP/2
cleartext (h2c) upgrade for a `POST` with a request body, so the JDK default of
HTTP/2 would wedge the first `POST /session`.

```
opencode/src/main/scala/orca/tools/opencode/
  OpencodeServer.scala       // shared serve process: start, base URL, HTTP wiring, teardown
  OpencodeHttp.scala         // transport trait: postJson, events (SSE), close
  JavaNetOpencodeHttp.scala  // OpencodeHttp over java.net.http (HTTP/1.1, basic auth)
  OpencodeBackend.scala      // LlmBackend[BackendTag.Opencode.type]
  OpencodeConversation.scala // one turn: SSE line source → StreamConversation; prompt_async; ask_user/permission replies
  OpencodeApi.scala          // request/response DTOs + jsoniter codecs (message body, SSE event envelope, AssistantMessage)
  OpencodeArgs.scala         // serve argv + message-body assembly from LlmConfig
  OpencodeModel.scala        // provider/model id construction + split
  DefaultOpencodeTool.scala  // OpencodeTool: provider-prefixed accessors, withModel, copyTool
adr/0014-opencode-server-driver.md
```

## Shared-type changes (in `tools`)

- `orca.llm.BackendTag`: add `case Opencode`.
- `orca.llm.LlmTool`: add `trait OpencodeTool extends LlmTool[BackendTag.Opencode.type]`
  with provider-prefixed model accessors — Anthropic
  (`anthropicOpus`/`anthropicSonnet`/`anthropicHaiku`) and OpenAI
  (`openaiGpt5`/`openaiGpt5Codex`/`openaiGpt5Mini`), plus a public
  `withModel(providerModel: String)` for any other `provider/model` (names below).
- `orca.llm.CanAskUser`: add `given CanAskUser[BackendTag.Opencode.type]` — the
  server supports native questions on interactive turns.
- No change to `LlmBackend`, `Conversation`, `LlmResult`, `Usage`, `DefaultLlmCall`,
  `BaseLlmTool` — OpenCode fits the existing SPI.

## Backend design

### OpencodeServer (shared, lazily started)

**Lazily constructed on first use** (the first `runAutonomous`/`runInteractive`
call), then shared for the rest of the Ox scope. A backend that's wired into the
flow context but never used — e.g. a flow that only touches claude/codex — starts
no `opencode serve` process and opens no connection. `OpencodeBackend` guards the
instance behind a thread-safe once-init (the spawn + health check race-free under
concurrent first calls). Responsibilities:
- Spawn `opencode serve --port 0 --log-level WARN` via `CliRunner.spawnPiped`,
  scan stdout for `listening on http://HOST:PORT`, capture the base URL.
- Generate and pass `OPENCODE_SERVER_PASSWORD` (basic auth) so the bound port
  isn't an open agent endpoint; the HTTP client sends matching credentials.
- Inherit provider creds from the JVM env (`ANTHROPIC_API_KEY` etc.) or rely on
  the user's `opencode auth` `auth.json` — same auth story as the other CLIs.
- Hold the `OpencodeHttp` client; expose `postJson` and a streaming `events` whose
  response body the reader fork consumes line-by-line (`java.net.http`'s
  `BodyHandlers.ofInputStream` — closing that `InputStream` is what unblocks the
  reader at turn end; `ofLines().close()` does not).
- Register teardown (SIGINT the process, close client + event stream) via
  `releaseAfterScope`.
- Health: block until `GET /doc` returns `200` (verified; `/` may 404) before
  returning.

#### Event stream — per-conversation SSE

Each `OpencodeConversation` owns one `GET /event` SSE connection and starts its turn
with `POST …/prompt_async` (`204`). The SSE stream is the single source: the reader
fork translates events to `ConversationEvent`s (mapping under *Progress events*) and
derives the `LlmResult` from the final `message.updated` (`info.structured` +
`info.tokens`) at `session.idle` — the single-stream shape Codex gets from
`turn.completed`.

This reuses `StreamConversation`: reader thread → bounded `EventQueue`
(`LinkedBlockingQueue`, blocking-`put` backpressure) → single-consumer `events`
iterator → `outcomeRef` (CAS) → `awaitResult`. The only substitution is the line
source — the SSE response body instead of `process.stdoutLines`; backpressure flows
into that TCP stream as it would into a subprocess pipe. Either generalise
`StreamConversation` from `PipedCliProcess` to a line-source abstraction (`lines`,
`cancel`, terminal signal) so `OpencodeConversation` extends it, or reuse
`StreamConversation.EventQueue` + `Outcome` (`private[orca]`) and replicate the
short reader loop.

`GET /event` accepts only `directory`/`workspace` query params (verified), not a
session filter, so the stream is directory-wide (other sessions + `server.*`);
filter by `properties.sessionID` client-side, narrowing with `?directory=<workDir>`.
Open the SSE connection before `prompt_async` so no turn events are missed;
`session.created` is not needed. (`POST …/session/{id}/wait` is a per-session
completion barrier — `204`, no body — not an event source.) At high same-directory
concurrency a single shared `/event` reader could later replace per-conversation
streams; not needed for v1.

`OpencodeBackend` takes the `CliRunner` (consistent with Claude/Codex) and holds
the lazily-initialized `OpencodeServer` (started on first use, per above).
`sessions = SessionRegistry.ClientToServer[BackendTag.Opencode.type]` (server
allocates ids). Unlike Codex there is **no resume endpoint** — `dispatchFor`'s
`Resume(serverId)` just means "POST to the same `ses_…` via the normal `/message`
endpoint"; only `Fresh` does an extra `POST /session` first.

### Model identifiers

OpenCode models are `provider/model`. `Model` stays an opaque `String`; the tool's
accessors set provider-qualified ids, split in `OpencodeArgs` into
`{providerID, modelID}`. Since OpenCode is the **multi-provider** backend, the tool
ships accessors for more than one family:

- Anthropic: `anthropicOpus` → `anthropic/claude-opus-4-8`, `anthropicSonnet` →
  `anthropic/claude-sonnet-4-6`, `anthropicHaiku` → `anthropic/claude-haiku-4-5`.
- OpenAI: `openaiGpt5` → `openai/gpt-5.4`, `openaiGpt5Codex` →
  `openai/gpt-5.3-codex`, `openaiGpt5Mini` → `openai/gpt-5-mini` (exact pinned ids
  are a detail — any id from `opencode models` is valid; update as the catalog
  moves).

Accessors are **provider-prefixed** (unlike `ClaudeTool`'s bare `opus`/`sonnet`,
which can assume a single vendor) because OpenCode spans providers — the prefix
keeps the model's provider explicit at the call site and leaves room for more
families. Plus a public `withModel(providerModel: String)` for any other `provider/model`
(e.g. `ollama/llama3.1`, `myhost/qwen-coder`). `Model` is an opaque `String` with
no allowlist, so arbitrary ids pass straight through to the message body.

`LlmConfig.model = None` lets the server use its configured default. `variant`
(reasoning effort) maps from a future config field; out of scope for v1.

**Model-identifier utilities.** For self-hosted / custom providers, provide a tiny
constructor so callers don't hand-concatenate strings and `OpencodeArgs` doesn't
re-parse blindly:

```scala
object OpencodeModel:
  /** Build a provider-qualified id, e.g. OpencodeModel("ollama", "llama3.1"). */
  def apply(providerID: String, modelID: String): Model =
    require(providerID.nonEmpty && modelID.nonEmpty)
    Model(s"$providerID/$modelID")

  /** Split a `provider/model` id back into the wire pair (first `/` only —
      model ids may contain further slashes, e.g. `google/gemma-3n`). */
  def split(m: Model): (String, String) =
    Model.name(m).split("/", 2) match
      case Array(p, rest) if p.nonEmpty && rest.nonEmpty => (p, rest)
      case _ => throw OrcaFlowException(s"not a provider/model id: ${Model.name(m)}")
```

`OpencodeTool.withModel(providerModel)` and the prefixed accessors both go through
`OpencodeModel`; `OpencodeArgs` uses `split` to fill `model:{providerID, modelID}`.
The split-on-first-`/` matters: self-hosted model ids often contain slashes
(`lmstudio/google/gemma-3n-e4b`).

### User-hosted & custom providers (local / self-hosted models)

"User-hosted" — local runtimes (Ollama, LM Studio, llama.cpp) and self-hosted
OpenAI-compatible endpoints — is a **provider-config** concern, not a code change.
OpenCode resolves a `provider/model` id against the provider config the serve
process reads; orca just passes the id through. A custom provider is declared in
OpenCode's config (`@ai-sdk/openai-compatible`, or `@ai-sdk/openai` for endpoints
on `/v1/responses`):

```json
{
  "provider": {
    "myhost": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Self-hosted",
      "options": { "baseURL": "http://localhost:11434/v1",
                   "apiKey": "{env:MYHOST_KEY}",
                   "headers": { "Authorization": "Bearer …" } },
      "models": { "qwen-coder": { "name": "Qwen Coder" } }
    }
  }
}
```

Used as `model: {providerID: "myhost", modelID: "qwen-coder"}`, i.e. the
`provider/model` string `myhost/qwen-coder`.

**For v1, orca always uses the user's configured OpenCode config.** The spawned
`opencode serve` reads the user's global config (`~/.config/opencode/…`) and
`auth.json` like any opencode invocation, so any provider the user already set up —
including local Ollama/LM Studio and self-hosted endpoints — works with **zero orca
involvement**: orca only passes the `provider/model` id through. The one
requirement: do **not** pass `--pure` to `serve` (it drops external
plugins/providers).

Out of scope for v1 (noted for later): orca-supplied provider config via the
serve-process env (`OPENCODE_CONFIG` / `OPENCODE_CONFIG_CONTENT` /
`OPENCODE_AUTH_CONTENT`, present in the binary) or the runtime `PUT /config` /
`/auth/{providerID}` endpoints, for hermetic flows/tests that declare a provider
programmatically instead of relying on ambient user config.

### Asking the user (gating the `question` tool)

OpenCode's ask-user mechanism is a built-in tool registered as **`question`**
(binary: `name:"question"`; raises a `question.asked` event with a
`QuestionRequest{ questions:[{question, header, options, multiple, custom}] }`).
The message body's `tools` field is a `{name: boolean}` map, so we gate asking
**per turn** by that tool's name:

- **Autonomous** turns set `tools: { "question": false }` — nobody is there to
  answer, so the agent must not block on a question. This is the native analog of
  Claude/Codex simply *not wiring* the `ask_user` MCP tool on autonomous turns.
- **Interactive** turns leave `question` enabled; `question.asked` events surface
  as `ConversationEvent.UserQuestion` and are answered via `POST
  /question/{id}/reply`. This is what backs `CanAskUser[Opencode]` +
  `Conversation.canAskUser = true`.

**Confirmed (spike):** the tool is `question`; `tools:{"question":false}` removes it
from the turn (zero `question.asked` fired, agent reported it absent); the reply
round-trip works end-to-end (agent unblocks with the chosen label), via `POST
/question/{que_id}/reply` `{"answers":[[label]]}`. See *Spike results* for the exact
`question.asked` / `question.replied` payloads.

Both `runAutonomous` and `runInteractive` build an `OpencodeConversation` (per
*Event stream*: own SSE connection + `prompt_async`, result derived from the
stream); `runAutonomous` then drains it with `Conversations.drainAutonomous`
(→ `OrcaListener` progress + the `LlmResult`), exactly as the Codex backend does.

### runAutonomous

1. `sessions.dispatchFor(session)`: `Fresh` → `POST /session` (mints `ses_…`, may
   carry `model`/`agent`/`permission`); `Resume(serverId)` → reuse the `ses_…`.
2. Build `OpencodeConversation(serverId, body)` — opens the SSE connection (reader
   fork running) **then** fires `POST …/prompt_async` with body from
   `OpencodeArgs.message`: text part, model split, `system` = composed system
   prompt, `format` from `outputSchema`, `tools` per `AutoApprove`/`readOnly`
   **with `question:false`** (autonomous never asks — see *Asking the user*).
3. `Conversations.drainAutonomous(conv, events)` → emits `OrcaEvent`s and returns
   the `LlmResult` the reader built from `message.updated`/`session.idle`
   (`output` = `info.structured` serialised when `outputSchema` set, else accrued
   text; `usage` = `info.tokens`; `model` = `info.modelID`; `info.error` →
   `AgentTurnFailed`).
4. `sessions.commitSuccess(session, result.sessionId)`; return
   `result.copy(sessionId = session)` (hide server id, as Codex does).

### runInteractive

Return the live `OpencodeConversation`:
- `events`: the `EventQueue` iterator (single-consumer), terminating on
  `session.idle`/`session.error`.
- `awaitResult()`: joins the reader → the `LlmResult` it built from
  `message.updated`/`session.idle` (as *runAutonomous* step 3); inherited verbatim
  from `StreamConversation`. `Left(OrcaInteractiveCancelled)` after `cancel()`.
- `sendUserMessage(text)`: starts a **new** turn — `POST /session/{id}/message` on
  the same session (OpenCode, unlike Codex, takes follow-up turns). Note: a turn
  blocked on `ask_user` is resumed via `/question/{id}/reply`, *not* this.
- `canAskUser = true`: the message body leaves the `question` tool enabled (see
  *Asking the user*); on `question.asked`, emit `ConversationEvent.UserQuestion`
  whose `respond` closure does `POST /question/{requestID}/reply` (or
  `/question/{requestID}/reject` on cancel).
- `cancel()`: `POST /session/{id}/abort`, close the SSE connection; the reader sees
  EOF and finalises `Outcome.Cancelled` (StreamConversation's `cancel`, HTTP-close
  instead of SIGINT).
- Tool-approval (`permission.asked`): if the channel surfaces approvals, map to
  `ConversationEvent.ApproveTool` → `POST /permission/{id}/reply`; otherwise the
  permission config (below) pre-answers so no prompt fires.

`registerSession` override records client→server id (called post-drive by
`DefaultLlmCall`, as for Codex).

### Progress events (display) — reusing `drainAutonomous`

Tool calls and messages must display as the agent works, in **both** modes. Both
display paths consume the *same* `conv.events` stream of `ConversationEvent`s:
- **Autonomous** — `Conversations.drainAutonomous(conv, events)` consumes
  `conv.events` and emits `OrcaEvent`s on the `OrcaListener`
  (`AssistantToolCall`→`ToolUse`, buffered text + `AssistantTurnEnd`→
  `AssistantMessage`, `Error`→`Error`; auto-denies stray `ApproveTool`,
  auto-answers stray `UserQuestion`).
- **Interactive** — `Interaction.drive` consumes `conv.events` and renders them
  (text, tool calls, questions) live, plus drives `ask_user`/approvals.

So the single SSE → `ConversationEvent` translation covers message-and-tool display
in both modes — there is **nothing mode-specific** about it. The backend's only job
is to make `OpencodeConversation.events` yield `ConversationEvent`s from the SSE
stream; both the autonomous listener wiring and the interactive renderer are then
free and identical to the other backends.

SSE → `ConversationEvent` mapping. **Confirmed against a live server** (see *Spike
results*): every SSE event is an envelope `{id, type, properties}`; session-scoped
events carry `properties.sessionID` (filter to this conversation's). Text streams
via `message.part.delta`, not the `session.next.text.*` family (that only fired for
model/agent switches in the spike). Drive off the `message.part` lifecycle +
`message.updated` + `session.idle`:

| OpenCode SSE event (`type` + `properties`) | handling |
|---|---|
| `message.part.delta` `{field:"text", delta}` | `AssistantTextDelta(delta)`; accrue text for free-form `output` |
| `message.part.delta` `{field:"reasoning", delta}` | `AssistantThinkingDelta(delta)` (dropped by autonomous drain) |
| `message.part.updated` `{part:{type:"tool", tool, state:{status:"running", input}}}` | `AssistantToolCall(tool, input)` → `ToolUse` |
| `message.part.updated` `{part:{type:"tool", state:{status:"completed"/"error", output}}}` | `ToolResult(tool, ok, output)` (dropped: volume) |
| `message.updated` (assistant) | capture `info` (`structured`, `tokens`, `modelID`, `finish`, `error`) — source of the `LlmResult` |
| `question.asked` `{questions[], …}` | `UserQuestion(q, respond)` — `respond` POSTs `/question/{id}/reply` (interactive; autonomous gates the tool off) |
| `permission.asked` `{id:"per_…", permission, patterns, tool}` | `ApproveTool(permission, patterns, respond)` — `respond` POSTs `/permission/{id}/reply` `{reply:"once"\|"always"\|"reject"}` (autonomous drain auto-denies) |
| `session.idle` `{sessionID}` | build `LlmResult` from captured `info`, CAS `outcomeRef`, `AssistantTurnEnd`, close queue |
| `session.error` / `info.error` | `Outcome.Failed(AgentTurnFailed)`, close queue |

Ignore `session.status`/`session.updated`/`session.diff`/`session.next.*`/
`server.*` for display.

### Concurrency

Per conversation, the reader fork, `EventQueue` (bounded blocking, backpressure),
single-consumer iterator, and `outcomeRef` CAS come from `StreamConversation` (or
its reused `EventQueue`/`Outcome`). Beyond that:

- The line source is the SSE HTTP response (not `process.stdoutLines`); `cancel`
  closes that connection (not SIGINT); "clean exit without result" = the stream
  ended before `session.idle`.
- Open the SSE connection before `prompt_async` (source-before-turn).
- `OpencodeServer` lazy once-init is thread-safe (CAS/guarded); a failed init isn't
  cached as success.
- `OrcaListener` is already thread-safe; reader-thread emissions need no extra
  synchronisation.

### Config mapping (`OpencodeArgs`)

| `LlmConfig` | OpenCode |
|---|---|
| `model` | message `model: {providerID, modelID}` (split on first `/`) |
| `systemPrompt` | message `system` (native field — no prompt-folding needed, unlike Codex) |
| `readOnly` | message `tools:{write:false, edit:false, bash:false}` (+`patch` if present) — disables the write tools (confirmed: a disabled tool vanishes from the agent's toolset) |
| `autoApprove = All` | leave tools enabled; reply `once`/`always` to any `permission.asked` (or run with `permission` defaulting to `allow`) |
| `autoApprove = Only(set)` | enable listed tools; for the rest, reply `reject` to their `permission.asked` (config `permission:{<tool>:"ask"}` makes them prompt) |
| `outputSchema` | message `format: {type:"json_schema", schema}` |
| ask-user (autonomous vs interactive) | message `tools: {"question": <bool>}` — `false` autonomous, enabled interactive |
| `selfManagedGit`, `retrySchedule` | orchestrator layer, no wire shape (unchanged) |

OpenCode's per-agent `permission` config (`opencode.json` / agent files) governs
ask-vs-allow-vs-deny, but the **message body accepts `tools` and the server honours
`format`/`system` per call** — and the spike confirmed a per-message
`tools:{name:false}` fully removes a tool from the turn. So `readOnly` is enforced
the strong way (disable the write tools outright), not via ask-then-deny, with no
config file needed. Live approval (`permission.asked` → `POST /permission/{id}/reply`
`once`/`always`/`reject`) is **interactive-only**: the autonomous drain can only
*deny* an `ApproveTool`, so **autonomous turns must see permission default to
`allow`** for the tools they keep enabled — they cannot approve. The server default
is `allow` (verified: bash ran without a config), so the common case works; but a
user `opencode.json` that globally sets `permission:"ask"` would make autonomous
turns auto-deny every tool. Mitigation: on the autonomous path, don't rely on the
ambient default — disable the write tools for `readOnly` and otherwise leave tools
enabled with approvals never prompted (autonomous never replies once/always).

### Structured output

Native: set `format`, read `info.structured`. **Confirmed against a live server**
(gpt-4o-mini): a `format:{type:"json_schema", schema}` request returns the validated
object on `info.structured` (e.g. `{"company":"Anthropic","founded":2021,
"products":[…]}`), and `info.finish == "tool-calls"`. The server injects a tool
named `StructuredOutput`; in structured mode the message has **no `text` part** —
`parts` are `step-start` / `tool` / `step-finish`, and the tool part holds the same
object at `state.input` with `state.metadata.valid == true`. So the backend reads
`info.structured` for the payload (not text parts). Free-form turns instead carry a
`text` part and `finish == "stop"`.

The existing `DefaultLlmCall` retry-on-bad-JSON loop remains as a backstop (and
covers `retryCount` exhaustion → `StructuredOutputError`). This is strictly better
than Codex's resume path, which falls back to prompt-only enforcement.

## Wiring

`DefaultFlowContext.withDefaults` gains an `opencode: Option[OpencodeTool] = None`
param, defaulting to `new DefaultOpencodeTool(new OpencodeBackend(OsProcCliRunner),
LlmConfig.default, prompts, workDir, dispatcher, interaction)`. Expose it on
`FlowContext` next to `claude`/`codex`.

## Implementation steps

1. **Spike (no orca code) — DONE.** Drove a live `opencode serve` by hand (`POST
   /session`, `POST …/message` with `format`, `GET /event`) against `openai/
   gpt-4o-mini`. Confirmed:
   - `POST …/message` is synchronous (~5s) → `{info: AssistantMessage, parts}`,
     `200`.
   - Structured: `format:{type:"json_schema",schema}` → validated object on
     `info.structured`; `finish:"tool-calls"`; parts `step-start`/`tool`/
     `step-finish` (tool = `StructuredOutput`, `state.input` = the object,
     `state.metadata.valid:true`); **no text part**.
   - Free-form: `text` part with the reply; `finish:"stop"`.
   - `info.tokens` = `{total,input,output,reasoning,cache:{read,write}}`;
     `info.cost` (number); `info.modelID`/`providerID`.
   - SSE `/event`: envelope `{id,type,properties}`; first event `server.connected`
     (empty `properties`); text via `message.part.delta`
     `{field:"text",delta}`; lifecycle via `message.part.updated`
     (`properties.part`); turn end `session.idle` `{sessionID}`. The
     `session.next.text.*` family did **not** carry text (only model/agent
     switches) — drive off `message.part.*`.

   Also confirmed (anthropic/claude-haiku-4-5):
   - **ask_user round-trip.** Forcing the agent to ask emits `question.asked` with
     `properties` = `{id:"que_…", sessionID, questions:[{question, header,
     options:[{label,description}], multiple?, custom?}], tool:{messageID,callID}}`.
     Reply via `POST /question/{que_id}/reply` body `{"answers":[["Blue"]]}` (one
     array per question, selected labels) → `200`; `question.replied`
     `{sessionID, requestID, answers}` fires; the agent unblocks and uses the
     answer. Reject via `POST /question/{que_id}/reject` (no body).
   - **Per-message `tools:{name:false}` disables a tool.** Sending the same forcing
     prompt with `tools:{"question":false}` produced **zero** `question.asked`; the
     agent reported `question` absent from its toolset. This validates the
     autonomous `question:false` gate *and* the `readOnly` mapping (same map).
   - **Default tool set** (server "build" agent): `bash, edit, glob, grep, read,
     skill, task, todowrite, webfetch, write` (+`question` when enabled) — so
     `readOnly` ⇒ disable `write, edit, bash` (and `patch` if the active agent
     exposes it; not in this set).

   - **permission.asked round-trip.** With config `permission:{bash:"ask"}`
     (loaded via `OPENCODE_CONFIG=<path>` — also confirmed; `GET /config` echoes
     it), a bash call emits `permission.asked` with `properties` = `{id:"per_…",
     sessionID, permission:"bash", patterns:["echo …"], always:["echo *"],
     metadata:{}, tool:{messageID,callID}}`. Reply via `POST
     /permission/{per_id}/reply` body `{"reply":"once"|"always"|"reject"}` → `200`;
     `permission.replied` `{sessionID, requestID, reply}` fires; on `once` the tool
     runs (`tool:"bash"`, `state.status:"completed"`, `state.output`). Maps to
     `ConversationEvent.ApproveTool` (Allow→`once`/`always`, Deny→`reject`).

   *Environment note:* `opencode serve` cannot be launched from the agent's own
   command path (its process tree is reaped at exec — root cause is the agent
   harness wrapper, not sandcat/seccomp/the container, which has no custom
   `SecurityOpt`). Workaround that worked: launch it under a separate daemon's
   process tree — `tmux new-session -d "opencode serve --port N"` survives and is
   reachable over `127.0.0.1`; the spike drove that. Both providers work through
   sandcat's placeholder-key injection (OpenAI gpt-4o-mini, Anthropic
   claude-haiku-4-5; Anthropic shows `tokens.cache.write` — prompt caching). SSE
   also emits periodic `server.heartbeat` keepalives (ignore for display). **All
   core wire contracts are now empirically confirmed.**
2. (Optional but recommended) generalise `StreamConversation` from `PipedCliProcess`
   to a small line-source abstraction (`lines`, `cancel`, terminal signal) so both
   subprocess and SSE backends share it; else reuse `EventQueue`/`Outcome` directly.
3. `opencode` sbt module + deps; add `BackendTag.Opencode`, `OpencodeTool`,
   `CanAskUser` given, `OpencodeModel`.
4. `OpencodeApi` DTOs + jsoniter codecs (message body, SSE envelope, AssistantMessage)
   from the captured payloads.
5. `OpencodeServer`: spawn/health/teardown/auth + HTTP client (incl. streaming
   `GET /event`).
6. `OpencodeArgs`: serve argv + message-body assembly.
7. `OpencodeConversation`: SSE line source → events + `LlmResult` from
   `message.updated`/`session.idle`; ask_user/permission replies; abort.
8. `OpencodeBackend.runAutonomous` (`prompt_async` + `Conversations.drainAutonomous`).
9. `OpencodeBackend.runInteractive` + `registerSession`.
10. `DefaultOpencodeTool` + `DefaultFlowContext`/`FlowContext` wiring.
11. Tests (below).

## Testing

Mirror the existing three-layer pattern (`*BackendTest` fakes, flow-level stubs,
`*IntegrationTest` gated on `ORCA_INTEGRATION`).

- **Unit — `OpencodeConversation`/`OpencodeArgs` over a fake SSE source.** OpenCode
  is HTTP not subprocess, so instead of `FakePipedCliProcess`/`SpawnStubCliRunner`,
  feed a canned list of SSE `data:` lines (captured in *Spike results*) into the
  conversation's line source and assert the `ConversationEvent` sequence + the
  `LlmResult` (structured object, `tokens`, model, `finish`, `info.error` →
  `AgentTurnFailed`). Separately unit-test `OpencodeArgs.message` body assembly
  (model split incl. multi-slash ids, `format`, `system`, `readOnly` tools,
  `question:false`) and `OpencodeModel.split`. Inject a stub sttp backend for the
  `prompt_async`/reply POSTs and assert the request bodies.
- **Flow-level e2e (no server) — a simple `implement.sc`-like plan via OpenCode.**
  Run the real `Plan` DSL (`Plan.autonomous.from` + `implementTaskLoop`) against a
  `TestFlowContext` whose `opencode` is a scripted stub (the `CannedResultLlm` /
  `OrcaOverridesTest` pattern): assert a 1-task plan is produced and the task loop
  drives `opencode.autonomous.run`/`resultAs`. Exercises the wiring end-to-end
  without a live server.
- **Integration (opt-in, `ORCA_INTEGRATION`, needs provider creds)** — mirror
  `Claude/CodexIntegrationTest`: `SupervisedBackend.using(new OpencodeBackend(
  OsProcCliRunner))`, which spawns a real `opencode serve` (the reaper that blocked
  this in the agent harness does **not** apply to a normal JVM/CI run). Cover:
  autonomous structured call returns a validated object; multi-turn resume recalls
  context; `ask_user` round-trips; `readOnly` (tools disabled) prevents a write;
  and a **small real plan** ("create file X containing Y, in one task") run through
  the flow to assert the file exists.

## Risks / open questions

- **Server lifecycle**: one shared long-lived process is a new ownership pattern
  vs. per-turn subprocesses. Crash detection, restart, and scope teardown need
  care.
- **SSE as the single result source**: the one new piece is a streaming HTTP client
  (sttp + ox) read line-by-line; the rest reuses `StreamConversation`/`EventQueue`.
  If the connection drops before `session.idle`, fall back to `GET
  /session/{id}/message` (or the blocking `POST …/message`, which carries the same
  `info`) rather than failing the turn.
- **Autonomous approval**: the autonomous drain can only deny, so autonomous turns
  depend on permission defaulting to `allow` (see *Config mapping*); a user config
  with `permission:"ask"` would break them.
- **Auth**: relies on env creds / `opencode auth`; document the requirement.

## Consequences

A third backend that, unlike Claude/Codex, (a) talks HTTP+SSE to a shared
long-lived server rather than spawning per-turn CLIs, (b) needs **no** MCP host
bridge because `ask_user` is native, and (c) gets native structured-output
enforcement on every turn (autonomous and interactive), not just the fresh path.
