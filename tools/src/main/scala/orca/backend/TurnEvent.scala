package orca.backend

/** Event a backend emits during one turn. A turn is a sequence of these,
  * terminated by the `events` iterator on [[LiveTurn]] closing; the final
  * outcome (success or cancel) is read via [[LiveTurn.awaitResult]].
  *
  * The deltas stream as the agent responds. `AssistantToolCall` is purely
  * informational; `ToolResult` echoes what the SDK reported back to the model.
  * `Approval` and `Question` carry the [[ChannelEvent]]s that must be answered.
  *
  * Distinct from [[OrcaEvent]], which fans out flow-wide: [[ObservedTurn]]
  * turns these into `OrcaEvent`s, handing the channel only the
  * [[ChannelEvent]]s.
  *
  * ==Message grammar (the contract every decoder honours)==
  *
  * A turn holds zero or more assistant *messages*. A message starts at the
  * first assistant activity (`AssistantTextDelta` / `AssistantThinkingDelta` /
  * `AssistantToolCall` / `ToolResult` / `ToolDenied`) after the stream start or
  * the previous `AssistantMessageEnd`. A `ToolResult` counts — a tool ran in
  * the message, so a completed-tool-only message is not empty — and so does a
  * `ToolDenied`, which stands in for one.
  *
  * Every message the wire *completed* — the backend reported its end, or the
  * decoder settled the turn, whether in success or failure — is terminated by
  * exactly one `AssistantMessageEnd`. A missing trailing `AssistantMessageEnd`
  * is legal only when the stream terminates abnormally mid-message; consumers
  * must flush at end-of-stream (as [[ObservedTurn.drain]] does).
  *
  * `AssistantMessageEnd` never fires without assistant activity since the last
  * one — there are no empty messages.
  *
  * `ToolResult.toolName` is `Some(name)` when the wire carries the name and
  * `None` when it doesn't (claude's `tool_result` blocks carry only a tool-use
  * id). It is never `Some("")`.
  *
  * [[opensMessage]] is the single source of truth for the activity/neutral
  * split above, dispatched on by both [[DecodedTurn]] (the funnel) and
  * [[orca.backend.TurnEventConformance]] (the oracle that asserts this grammar
  * over a recorded sequence).
  */
private[orca] enum TurnEvent:
  /** The opening prompt of an interactive turn. Rendered so the user sees
    * context for their own input alongside agent output.
    */
  case UserMessage(text: String)
  case AssistantTextDelta(text: String)
  case AssistantThinkingDelta(text: String)
  case AssistantToolCall(toolName: String, rawInput: String)
  case ToolResult(toolName: Option[String], ok: Boolean, content: String)

  /** A tool call the harness refused for lack of permission, in place of its
    * `ToolResult`. Only claude's wire tells a refusal apart from a failed tool;
    * opencode's refusals arrive as `Approval`, and codex's and gemini's are
    * indistinguishable from tool failures, so they surface as `ToolResult`.
    */
  case ToolDenied(toolName: String)
  case AssistantMessageEnd

  /** Non-fatal error surfaced mid-turn (e.g. a line from the subprocess's
    * stderr). Distinct from turn-ending failures, which surface as exceptions
    * on [[LiveTurn.awaitResult]].
    */
  case Error(message: String)

  /** A [[ChannelEvent.ApproveTool]] for the channel to answer. */
  case Approval(request: ChannelEvent.ApproveTool)

  /** A [[ChannelEvent.UserQuestion]] for the channel to answer. Only emitted by
    * backends whose [[LiveTurn.canAskUser]] is true.
    */
  case Question(request: ChannelEvent.UserQuestion)

  /** True for the events the "Message grammar" scaladoc above classifies as
    * assistant activity (open/continue a message); false for neutral events
    * that never affect message state. Deliberately exhaustive — no wildcard arm
    * — so a future case is a compile error here until explicitly classified.
    * `AssistantMessageEnd` classifies as `false` (neutral): it has its own
    * forward/drop and assertion arms ahead of this split, in the funnel and the
    * oracle respectively.
    */
  def opensMessage: Boolean = this match
    case TurnEvent.AssistantTextDelta(_)     => true
    case TurnEvent.AssistantThinkingDelta(_) => true
    case TurnEvent.AssistantToolCall(_, _)   => true
    case TurnEvent.ToolResult(_, _, _)       => true
    case TurnEvent.ToolDenied(_)             => true
    case TurnEvent.UserMessage(_)            => false
    case TurnEvent.Error(_)                  => false
    case TurnEvent.Approval(_)               => false
    case TurnEvent.Question(_)               => false
    case TurnEvent.AssistantMessageEnd       => false

/** The events a turn's background drains (stderr, `ask_user`) and its opening
  * prompt may send: none of them affects the message grammar.
  */
private[orca] type NeutralEvent = TurnEvent.UserMessage | TurnEvent.Error |
  TurnEvent.Question
