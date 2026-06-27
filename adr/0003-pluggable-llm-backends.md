# 0003. LLM backends are pluggable behind an `AgentBackend[B <: Backend]` trait

Status: Accepted · Date: 2026-04-22 (updated 2026-04-23)

## Decision

`AgentBackend[B]` exposes `runHeadless`, `continueHeadless`,
`runInteractive`, and `continueInteractive`. The type parameter
`B <: Backend` (e.g. `Backend.ClaudeCode.type`) makes `SessionId[B]`,
`AgentResult[B]`, and `Conversation[B]` phantom-typed so a Claude session
id can't accidentally resume a Codex session.

The Claude backend shells out to the `claude` CLI via a `CliRunner`
abstraction. Headless calls use `claude -p --output-format json`.
Interactive calls open a stream-json subprocess (see ADR 0006) and
wrap it in a `ClaudeConversation` that the channel's
`Interaction.drive` consumes as a typed event stream — no transcript
markers, no filesystem sentinels.

Codex will run via WebSocket (sttp) on the same trait.

## Why

- Each backend (Claude Code, Codex) already ships a CLI or app-server
  with session management; reimplementing that in-process buys nothing
  and loses features (tool invocation, MCP servers, per-turn audit).
- Keeping the interface minimal (four methods) lets us add backends
  without touching the DSL.
- Phantom-typed session ids mean the compiler rejects the
  `continueHeadless(codexSid, ...)` mistake against a Claude backend.

## Consequences

- The `CliRunner` trait is the test seam: every backend test uses a
  `StubCliRunner` that records args and returns canned responses, or
  a `FakePipedCliProcess` for the stream-json subprocess path.
  Integration tests against the real CLI are gated on
  `ORCA_INTEGRATION`.
- Interactive completion is signalled by a typed `result` message on
  the stream-json subchannel (ADR 0006), carrying the session id,
  usage, and — when `--json-schema` is passed — a validated
  `structured_output`. No filesystem sentinels; no platform
  assumptions.
- Adding a non-CLI backend (e.g. a raw API client) stays possible
  because `AgentBackend` isn't tied to `CliRunner` — only the Claude
  backend is.
