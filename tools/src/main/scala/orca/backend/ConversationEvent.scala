package orca.backend

/** Event the driver emits for the channel to render. One session is a sequence
  * of these, terminated by the `events` iterator on [[Conversation]] closing;
  * the final outcome (success or cancel) is read via
  * [[Conversation.awaitResult]].
  *
  * The deltas stream as the agent responds. `AssistantToolCall` is purely
  * informational; `ToolResult` echoes what the SDK reported back to the model.
  * `ApproveTool` is the only event the channel must respond to.
  *
  * Distinct from [[OrcaEvent]], which fans out flow-wide; conversation events
  * stay between driver and channel.
  *
  * ==Turn grammar (the contract every driver honours)==
  *
  * A *turn* starts at the first assistant activity (`AssistantTextDelta` /
  * `AssistantThinkingDelta` / `AssistantToolCall` / `ToolResult`) after the
  * stream start or the previous `AssistantTurnEnd`. A `ToolResult` counts — a
  * tool ran in the turn, so a completed-tool-only turn is not empty.
  *
  * Every turn the wire *completed* — the backend reported a turn end, or the
  * conversation settled, whether in success or failure — is terminated by
  * exactly one `AssistantTurnEnd`. A missing trailing `AssistantTurnEnd` is
  * legal only when the stream terminates abnormally mid-turn; consumers must
  * flush at end-of-stream (as [[Conversations.drainAutonomous]] does).
  *
  * `AssistantTurnEnd` never fires without assistant activity since the last one
  * — there are no empty turns.
  *
  * `ToolResult.toolName` is `Some(name)` when the wire carries the name and
  * `None` when it doesn't (claude's `tool_result` blocks carry only a tool-use
  * id). It is never `Some("")`.
  *
  * [[opensTurn]] is the single source of truth for the activity/neutral split
  * above, dispatched on by both [[ForkedConversation.EventQueue.enqueue]] (the
  * funnel) and [[orca.backend.ConversationEventConformance]] (the oracle that
  * asserts this grammar over a recorded sequence).
  */
enum ConversationEvent:
  /** A user turn — the opening prompt (emitted by the driver at session start)
    * or a mid-session reply. Rendered so the user sees context for their own
    * input alongside agent output.
    */
  case UserMessage(text: String)
  case AssistantTextDelta(text: String)
  case AssistantThinkingDelta(text: String)
  case AssistantToolCall(toolName: String, rawInput: String)
  case ToolResult(toolName: Option[String], ok: Boolean, content: String)
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

  /** The agent wants a free-form answer from the user. The channel displays
    * `question`, reads a reply, and calls `respond` exactly once with what the
    * user typed; the driver feeds the answer back as a tool result.
    *
    * Only emitted by backends whose [[Conversation.canAskUser]] is true —
    * claude and codex (both via the shared `AskUserMcpServer`).
    */
  case UserQuestion(question: String, respond: String => Unit)

  /** True for the events the "Turn grammar" scaladoc above classifies as
    * assistant activity (open/continue a turn); false for neutral events that
    * never affect turn state. Deliberately exhaustive — no wildcard arm — so a
    * future case is a compile error here until explicitly classified.
    * `AssistantTurnEnd` classifies as `false` (neutral): it has its own
    * forward/drop and assertion arms ahead of this split, in the funnel and the
    * oracle respectively.
    */
  def opensTurn: Boolean = this match
    case ConversationEvent.AssistantTextDelta(_)     => true
    case ConversationEvent.AssistantThinkingDelta(_) => true
    case ConversationEvent.AssistantToolCall(_, _)   => true
    case ConversationEvent.ToolResult(_, _, _)       => true
    case ConversationEvent.UserMessage(_)            => false
    case ConversationEvent.Error(_)                  => false
    case ConversationEvent.ApproveTool(_, _, _)      => false
    case ConversationEvent.UserQuestion(_, _)        => false
    case ConversationEvent.AssistantTurnEnd          => false

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
