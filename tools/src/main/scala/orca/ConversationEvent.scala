package orca

/** Event the driver emits for the channel to render. One full session is
  * represented by a sequence of these, terminated by the `events` iterator
  * on [[Conversation]] closing; the final outcome (success or cancel) is
  * read via [[Conversation.awaitResult]].
  *
  * Partial deltas (`AssistantTextDelta`, `AssistantThinkingDelta`) arrive
  * as the agent streams its response. `AssistantTurnEnd` marks the
  * boundary between turns. `AssistantToolCall` is purely informational —
  * the agent narrated a tool invocation. `ToolResult` echoes what the
  * SDK reported back to the model after running the tool. `ApproveTool`
  * is the only event the channel must respond to — it carries a
  * `respond` closure the channel invokes exactly once with its decision.
  */
enum ConversationEvent:
  case AssistantTextDelta(text: String)
  case AssistantThinkingDelta(text: String)
  case AssistantToolCall(toolName: String, rawInput: String)
  case ToolResult(toolName: String, ok: Boolean, content: String)
  case AssistantTurnEnd

  /** Non-fatal error surfaced mid-session (e.g. a line from the
    * subprocess's stderr). Distinct from session-ending failures, which
    * surface as exceptions on [[Conversation.awaitResult]].
    */
  case Error(message: String)

  /** The agent wants to invoke a tool and is asking our permission.
    * The channel must call `respond` exactly once — `Allow(...)` to
    * execute, `Deny(...)` to refuse. The driver owns the matching
    * request-id bookkeeping; the closure captures it.
    */
  case ApproveTool(
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit
  )
