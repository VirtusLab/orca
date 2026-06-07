package orca.llm

/** Compile-time capability typeclass: an instance exists iff the backend tagged
  * `B` exposes a host-side `ask_user` tool — i.e. interactive sessions on that
  * backend can pause to ask the user a free-form question and resume with the
  * typed answer.
  *
  * Used as a constraint on flow helpers that depend on the capability:
  *
  * {{{
  *   def from[B <: BackendTag: CanAskUser](llm: LlmTool[B], ...): T
  * }}}
  *
  * Calling with a backend that lacks an instance is a compile error.
  *
  * Mirrors `Conversation.canAskUser` at the type level. The runtime flag stays
  * useful for programmatic queries on a concrete `Conversation[?]`; the
  * typeclass catches mistakes one step earlier — at the
  * `Plan.interactive.from(claude/codex/pi, …)` call site — without requiring
  * pattern-matching on `B`.
  */
trait CanAskUser[B <: BackendTag]

object CanAskUser:
  /** Claude's interactive sessions wire up an MCP `ask_user` tool the agent can
    * call (see `orca.backend.mcp.AskUserMcpServer`).
    */
  given CanAskUser[BackendTag.ClaudeCode.type] =
    new CanAskUser[BackendTag.ClaudeCode.type] {}

  /** Codex's interactive sessions register the same shared
    * [[orca.backend.mcp.AskUserMcpServer]] via codex's MCP support (`-c
    * mcp_servers.orca.url=…`), so the agent calls `ask_user` the same way
    * claude does. Stdin isn't a viable channel (codex exec consumes it once —
    * ADR 0007), but the MCP path doesn't need it.
    */
  given CanAskUser[BackendTag.Codex.type] =
    new CanAskUser[BackendTag.Codex.type] {}

  /** OpenCode's interactive sessions expose a native `question` tool (no MCP
    * bridge); `question.asked` events surface as
    * `ConversationEvent.UserQuestion` and are answered via the server's
    * `/question/{id}/reply` endpoint (ADR 0014).
    */
  given CanAskUser[BackendTag.Opencode.type] =
    new CanAskUser[BackendTag.Opencode.type] {}

  /** Pi's interactive sessions load Orca's temporary ask-user extension, which
    * forwards extension UI prompts into the same Orca `UserQuestion` event used
    * by the other interactive backends.
    */
  given CanAskUser[BackendTag.Pi.type] =
    new CanAskUser[BackendTag.Pi.type] {}

  /** Gemini's interactive sessions register the shared
    * [[orca.backend.mcp.AskUserMcpServer]] via a project-local
    * `.gemini/settings.json` `mcpServers.orca` entry (gemini has no inline MCP
    * flag like codex), so the agent calls `ask_user` the same way claude and
    * codex do. See [[adr/0015-gemini-stream-json-driver.md ADR 0015]].
    */
  given CanAskUser[BackendTag.Gemini.type] =
    new CanAskUser[BackendTag.Gemini.type] {}
