package orca.backend

/** Event a backend driver emits for one turn. One session is a sequence of
  * these, terminated by the `events` iterator on [[Conversation]] closing; the
  * final outcome (success or cancel) is read via [[Conversation.awaitResult]].
  *
  * The deltas stream as the agent responds. `AssistantToolCall` is purely
  * informational; `ToolResult` echoes what the SDK reported back to the model.
  * `ApproveTool` and `UserQuestion` ([[ChannelEvent]]) must be answered.
  *
  * Distinct from [[OrcaEvent]], which fans out flow-wide:
  * [[ObservedConversation]] turns these into `OrcaEvent`s, handing the channel
  * only the [[ChannelEvent]]s.
  *
  * ==Turn grammar (the contract every driver honours)==
  *
  * A *turn* starts at the first assistant activity (`AssistantTextDelta` /
  * `AssistantThinkingDelta` / `AssistantToolCall` / `ToolResult` /
  * `ToolDenied`) after the stream start or the previous `AssistantTurnEnd`. A
  * `ToolResult` counts — a tool ran in the turn, so a completed-tool-only turn
  * is not empty — and so does a `ToolDenied`, which stands in for one.
  *
  * Every turn the wire *completed* — the backend reported a turn end, or the
  * conversation settled, whether in success or failure — is terminated by
  * exactly one `AssistantTurnEnd`. A missing trailing `AssistantTurnEnd` is
  * legal only when the stream terminates abnormally mid-turn; consumers must
  * flush at end-of-stream (as [[ObservedConversation.drain]] does).
  *
  * `AssistantTurnEnd` never fires without assistant activity since the last one
  * — there are no empty turns.
  *
  * `ToolResult.toolName` is `Some(name)` when the wire carries the name and
  * `None` when it doesn't (claude's `tool_result` blocks carry only a tool-use
  * id). It is never `Some("")`.
  *
  * [[opensTurn]] is the single source of truth for the activity/neutral split
  * above, dispatched on by both [[StreamConversation]] (the funnel) and
  * [[orca.backend.ConversationEventConformance]] (the oracle that asserts this
  * grammar over a recorded sequence).
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

  /** A tool call the harness refused for lack of permission, in place of its
    * `ToolResult`. Only claude's wire tells a refusal apart from a failed tool;
    * opencode's refusals arrive as `ApproveTool`, and codex's and gemini's are
    * indistinguishable from tool failures, so they surface as `ToolResult`.
    */
  case ToolDenied(toolName: String)
  case AssistantTurnEnd

  /** Non-fatal error surfaced mid-session (e.g. a line from the subprocess's
    * stderr). Distinct from session-ending failures, which surface as
    * exceptions on [[Conversation.awaitResult]].
    */
  case Error(message: String)

  /** The agent wants to invoke a tool and is asking our permission. The channel
    * must call `respond` exactly once — `Allow` to execute, `Deny` to refuse.
    * The driver owns the matching request-id bookkeeping; the closure captures
    * it.
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
    case ConversationEvent.ToolDenied(_)             => true
    case ConversationEvent.UserMessage(_)            => false
    case ConversationEvent.Error(_)                  => false
    case ConversationEvent.ApproveTool(_, _, _)      => false
    case ConversationEvent.UserQuestion(_, _)        => false
    case ConversationEvent.AssistantTurnEnd          => false

/** The events a conversation's background drains (stderr, `ask_user`) and its
  * opening prompt may send: none of them affects the turn grammar.
  */
type NeutralEvent = ConversationEvent.UserMessage | ConversationEvent.Error |
  ConversationEvent.UserQuestion

/** The events a channel must answer: the backend blocks until `respond` is
  * called. Everything else a conversation emits reaches listeners as an
  * `OrcaEvent` ([[ObservedConversation]]).
  */
type ChannelEvent = ConversationEvent.ApproveTool |
  ConversationEvent.UserQuestion

/** Channel's answer to a [[ConversationEvent.ApproveTool]] prompt. */
enum ApprovalDecision:
  case Allow, Deny
