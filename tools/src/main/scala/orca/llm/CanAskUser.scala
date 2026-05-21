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
  * `Plan.interactive.from(claude/codex, …)` call site — without requiring
  * pattern-matching on `B`.
  */
trait CanAskUser[B <: BackendTag]

object CanAskUser:
  /** Claude's interactive sessions wire up an MCP `ask_user` tool the agent can
    * call (see `orca.tools.claude.mcp.AskUserMcpServer`).
    */
  given CanAskUser[BackendTag.ClaudeCode.type] =
    new CanAskUser[BackendTag.ClaudeCode.type] {}

  // No instance for `BackendTag.Codex` — `codex exec` consumes stdin once
  // and has no mid-session user-message channel (ADR 0007).
