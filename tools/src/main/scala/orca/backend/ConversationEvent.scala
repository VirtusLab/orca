package orca.backend

/** Event the driver emits for the channel to render. One full session is
  * represented by a sequence of these, terminated by the `events` iterator on
  * [[Conversation]] closing; the final outcome (success or cancel) is read via
  * [[Conversation.awaitResult]].
  *
  * Partial deltas (`AssistantTextDelta`, `AssistantThinkingDelta`) arrive as
  * the agent streams its response. `AssistantTurnEnd` marks the boundary
  * between turns. `AssistantToolCall` is purely informational — the agent
  * narrated a tool invocation. `ToolResult` echoes what the SDK reported back
  * to the model after running the tool. `ApproveTool` is the only event the
  * channel must respond to — it carries a `respond` closure the channel invokes
  * exactly once with its decision.
  *
  * Distinct from [[OrcaEvent]], which fans out flow-wide; conversation events
  * stay between driver and channel.
  */
enum ConversationEvent:
  /** A user turn — either the opening prompt (emitted by the driver when the
    * session starts) or a mid-session reply. Letting the channel render these
    * alongside agent output gives the user visible context about their own
    * input; a long agent response to an unseen prompt feels unmoored.
    */
  case UserMessage(text: String)
  case AssistantTextDelta(text: String)
  case AssistantThinkingDelta(text: String)
  case AssistantToolCall(toolName: String, rawInput: String)
  case ToolResult(toolName: String, ok: Boolean, content: String)
  case AssistantTurnEnd

  /** Non-fatal error surfaced mid-session (e.g. a line from the subprocess's
    * stderr). Distinct from session-ending failures, which surface as
    * exceptions on [[Conversation.awaitResult]].
    */
  case Error(message: String)

  /** The agent wants to invoke a tool and is asking our permission. The channel
    * must call `respond` exactly once — `Allow(...)` to execute, `Deny(...)` to
    * refuse. The driver owns the matching request-id bookkeeping; the closure
    * captures it.
    */
  case ApproveTool(
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit
  )

/** Channel's answer to a [[ConversationEvent.ApproveTool]] prompt.
  *
  *   - `Allow(None)` — run the tool with its original input.
  *   - `Allow(Some(json))` — run the tool but substitute the input with the
  *     supplied JSON value; useful for edit-then-approve UIs.
  *   - `Deny(reason)` — refuse the call; `reason`, if given, is surfaced back
  *     to the agent so it can adapt.
  */
enum ApprovalDecision:
  case Allow(updatedInputJson: Option[String] = None)
  case Deny(reason: Option[String] = None)
