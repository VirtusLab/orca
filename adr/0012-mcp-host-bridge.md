# ADR 0012: MCP host bridge for mid-session user input

## Context

The `Plan.interactive.from` / `claude.resultAs[X].interactive` surface
promises that the agent can pause to ask the user a clarifying question
and resume with the typed answer. The naive routes were each blocked:

- **Stdin streaming.** `claude --print --input-format stream-json`
  batches all stdin user turns until EOF; the agent doesn't process
  turn-by-turn. Keeping stdin open just makes claude wait forever.
- **Claude Agent SDK** has a streaming-input mode, but the SDK is
  Python/TypeScript-only — not callable from Scala without a foreign
  sidecar process.
- **Prompt-engineered marker line** (agent emits `[ASK_USER]: …`,
  driver detects and replies) requires turn-by-turn processing, which
  the CLI mode above doesn't support.

The remaining viable path is **MCP** (Model Context Protocol): expose
an `ask_user` tool the agent can call. The agent treats it like any
other tool — invokes it, waits for the result, continues with the
result. Tool-call traffic in `--print` mode is fully bidirectional
already (that's how the CLI handles `Bash`, `Read`, etc.); no
streaming-stdin gymnastics needed.

## Decision

Stand up a tiny MCP HTTP server inside the orca process, advertised to
the spawned `claude` subprocess via `.mcp.json` + `--mcp-config`.

### Components

- **`AskUserBridge`** (`orca.tools.claude.mcp`) — synchronous rendezvous
  between the MCP handler thread (Netty worker) and the host. One
  `Channel[(question, replyChannel)]` queue; each `ask(q)` enqueues its
  own per-call reply channel, blocks on it, returns when the host
  signals. `take()` is the consumer side: returns the next pending
  question plus a closure that delivers the answer.

- **`AskUserMcpServer`** (`orca.tools.claude.mcp`) — wraps chimp's
  `mcpEndpoint` for one `ask_user(question: String): String` tool. Built
  on Tapir + Netty-sync; binds 127.0.0.1 on an ephemeral port. The
  handler is `in => Right(bridge.ask(in.question))` — chimp serialises
  the rest. Implements `AutoCloseable`; the server's `close()` stops
  the Netty binding.

- **`ClaudeBackend.openConversation`** — builds the bridge + server,
  writes `.orca-mcp-<port>.json` to `workDir` (port-suffixed so
  concurrent conversations sharing a workdir don't collide), passes
  `--mcp-config` to claude, and hands the bridge to
  `ClaudeConversation`. Adds the MCP tool name (`mcp__orca__ask_user`)
  to the auto-approved set so the user isn't prompted to approve the
  ask-question tool before the question itself.

- **`ClaudeConversation`** — daemon thread drains the bridge and
  surfaces `ConversationEvent.UserQuestion(text, respond)` on the
  conversation's event queue. The renderer prompts the user and calls
  `respond(answer)`, which signals the bridge, unblocking the MCP
  handler so it returns the answer as the tool result. The server is
  bundled into an `AskUserResources` (with the bridge and any
  backend-specific `extras` such as claude's workDir-local config
  file) and closed from the conversation's `onFinalize` so its Netty
  binding releases when the conversation ends — not when the outer
  flow scope tears down.

- **System-prompt hint** — `AskUserMcpServer.Hint` is appended on the
  interactive path (only). Tells the agent the tool exists and to use
  it conservatively. Worded to discourage overuse. Shared across
  backends; each delivers it differently (claude via
  `--append-system-prompt-file`, codex by folding into the user
  prompt — `SystemPromptComposer.combine` does the input-combining).

- **`Conversation.canAskUser`** — capability flag, `true` when the
  bridge is wired (claude interactive, codex interactive); `false`
  for autonomous calls. Flow code can branch on this before relying
  on mid-session Q&A; the compile-time `CanAskUser[B]` typeclass
  encodes the same capability at the call site.

### Why chimp

Already at SoftwareMill; built on Tapir-sync; ships an MCP-spec-compliant
JSON-RPC endpoint as a `ServerEndpoint[Any, Identity]` that drops into
the same Netty-sync stack the rest of the codebase uses. ~30 lines of
glue to register one tool. Pinned to `0.1.8` — bumped Tapir to `1.13.18`
and Ox to `1.0.2` to keep the classpath single-resolved.

### Why HTTP transport, not stdio

Two reasons:

1. Stdio MCP servers are spawned by Claude Code itself; getting a
   reference back to the orca host process for the `ask_user` handler
   would require either a side-channel (named pipe / Unix socket) or
   re-implementing the same dance via a wrapper script. HTTP on
   `127.0.0.1` is one fewer indirection.
2. The chimp 0.1.8 module ships HTTP only; the stdio transport would
   need separate machinery.

### Lifecycle

```
flow(...)                 // supervised: scope opens
  ClaudeBackend(...)      // captures using Ox, BufferCapacity
  ...
  interactive call:
    AskUserBridge         // per-conversation
    AskUserMcpServer      // per-conversation; Netty binding starts
    ClaudeConversation    // owns server via sessionResources
    ...
    process exits         // reader thread joins
    onFinalize            // ↳ sessionResources.foreach(_.close())
                          // ↳ Netty binding stops
flow exits                // supervised: scope tears down
                          // any Ox-scoped state released
```

The server's lifetime is bounded by the conversation, so a long flow
with many sequential interactive calls doesn't accumulate bindings.

### Auto-approval

The MCP tool name (`mcp__orca__ask_user`) is unioned into
`AgentConfig.autoApprove` for the conversation only — via
`AgentConfig.withAlsoAllowedTool`. The user is already typing an answer;
a y/n approval prompt before that would be pure noise.

### Render suppression

`ClaudeConversation.handleAssistantTurn` drops the `ContentBlock.ToolUse`
for `ask_user` so the renderer doesn't show "Tool call: ask_user {...}"
right above the question text. The `tool_result` echo on the next user
turn is *not* suppressed (the existing handler emits ToolResult with an
empty tool name and we don't track which result belongs to which
call); the answer appears as `⎿ <answer>` after the user types it.
Acceptable redundancy; refine later if needed.

## Consequences

**Positive**

- `Plan.interactive.from(prompt, claude)` now genuinely lets the agent
  ask follow-up questions, completing what the scaladoc had been
  promising since the start.
- No new long-running infrastructure: the MCP server is per-conversation
  and lives only as long as the conversation does.
- Codex is unaffected: `canAskUser = false` documents the asymmetry;
  Codex's `exec` subprocess can't accept mid-session input anyway
  (ADR 0007).

**Negative**

- Ports allocated per conversation. For typical flows (a few interactive
  calls) this is fine; for unusual flows that open many short
  interactive conversations in tight succession the port-allocation
  overhead exists. Ephemeral binding + immediate release on conversation
  end keeps it bounded.
- Tapir + Netty dependency surface added to the `claude` module. Builds
  produce a larger artifact; runtime startup gains a few classes.
- The agent occasionally over-uses `ask_user` despite the system-prompt
  guidance. May need stricter wording or a quota in a future iteration.

## Future work

- Cancellation: if the user Ctrl-Cs during the prompt, the MCP handler
  is left blocked on the reply channel. Today the bridge channel closes
  on scope tear-down and the handler exits; cleaner would be a
  per-call cancel that surfaces back to the agent as a tool error.
- Codex story: still requires `codex app-server` (rejected in ADR 0007)
  or a wrapper SDK process; no clean answer yet.
- Other host-side tools: the bridge + server pattern generalises. If we
  add e.g. `read_file_from_host` or `notify_user`, they belong in the
  same `mcp` sub-package alongside `ask_user`.
