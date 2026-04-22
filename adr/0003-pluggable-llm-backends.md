# 0003. LLM backends are pluggable behind an `LlmBackend[B <: Backend]` trait

Status: Accepted · Date: 2026-04-22

## Decision

`LlmBackend[B]` exposes `runHeadless`, `continueHeadless`,
`launchInteractive`, `resumeInteractive`, and `prepareWorkspace`. The
type parameter `B <: Backend` (e.g. `Backend.ClaudeCode.type`) makes
`SessionId[B]` and `LlmResult[B]` phantom-typed so a Claude session id
can't accidentally resume a Codex session.

The Claude backend shells out to the `claude` CLI via a `CliRunner`
abstraction and communicates structured completion through the
`<<<ORCA_DONE>>>` marker + a sentinel file the Stop hook writes.
Codex will run via WebSocket (sttp) on the same trait.

## Why

- Each backend (Claude Code, Codex) already ships a CLI or app-server
  with session management; reimplementing that in-process buys nothing
  and loses features (tool invocation, MCP servers, per-turn audit).
- Keeping the interface minimal (five methods) lets us add backends
  without touching the DSL.
- Phantom-typed session ids mean the compiler rejects the
  `continueHeadless(codexSid, ...)` mistake against a Claude backend.

## Consequences

- The `CliRunner` trait is the test seam: every backend test uses a
  `StubCliRunner` that records args and returns canned responses.
  Integration tests against the real CLI are gated on
  `ORCA_INTEGRATION`.
- Interactive completion relies on a filesystem sentinel
  (`/tmp/orca-<session-id>.json`). Platforms without a writable `/tmp`
  would need a backend override.
- Adding a non-CLI backend (e.g. a raw API client) stays possible
  because `LlmBackend` isn't tied to `CliRunner` — only the Claude
  backend is.
